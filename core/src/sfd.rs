//! SFD — „Schutz Fahrzeug Diagnose“ (VAG) über UDS.
//!
//! SFD sperrt sicherheitsrelevante Steuergeräte, bis ein **Token** gesendet
//! wird. Der Token wird nicht hier erzeugt: er kommt aus dem Herstellerportal
//! und wird von dort als Hex-Zeichenkette kopiert. Dieses Modul baut die
//! UDS-Nachrichten, prüft die Antworten und erkennt Ablehnungen — es erzeugt
//! keine Schlüssel und rechnet nichts nach.
//!
//! ## Aufbau der Nachrichten
//!
//! | Zweck | Anfrage | positive Antwort |
//! | --- | --- | --- |
//! | Status lesen | `22 01 74` | `62 01 74 …` |
//! | Challenge | `31 01 C0 04 <Rolle>` | `71 01 C0 04 <Challenge>` |
//! | Token senden | `31 01 C0 08 <Token>` | `71 01 C0 08` |
//! | sperren | `31 01 C0 09` | `71 01 C0 09` |
//! | Sitzung | `10 03` | `50 03` |
//!
//! `31` ist RoutineControl mit `startRoutine` (Subfunktion `01`). Die Kennung
//! ist zwei Byte breit (`C0 04`, `C0 08`, `C0 09`) — der Schritt steckt also im
//! **niedrigen** Byte der Routine, nicht in einem eigenen Feld. Die Rolle ist
//! ein Byte Option: welchen Wert ein Fahrzeug erwartet, steht in seiner
//! Diagnosebeschreibung; hier wird nur geprüft, dass sie nicht 0 ist.
//!
//! Gebaut werden diese Nachrichten in [`crate::uds::routine_start_anfrage`];
//! dieses Modul liefert nur Kennung und Optionen. Damit gibt es auch für `31`
//! einen Baukasten und nicht zwei.
//!
//! ## Länge
//!
//! Ein Token ist deutlich länger als ein CAN-Rahmen (typisch einige Hundert
//! Byte). Das ist der Grund, warum der Multi-Frame-Pfad in [`IsotpChannel`]
//! Blockgröße und Mindestabstand auswertet: ein Steuergerät, das `BS > 0` oder
//! `STmin > 0` verlangt, verwirft sonst Rahmen, und die Routine bricht mit
//! `N_Cr`-Zeitüberschreitung ab.
//!
//! ## Ablehnungen
//!
//! Jede Antwort kann statt der positiven auch `7F <Dienst> <NRC>` sein. Der NRC
//! wird als [`SfdError::Nrc`] zurückgegeben — samt Klartext, weil „abgelehnt“
//! allein in der Werkstatt nicht weiterhilft.
//!
//! ## Ein Sendepfad
//!
//! Gesendet wird über [`crate::uds::UdsClient::runde`] — dieselbe Stelle, die
//! jeder andere UDS-Dienst benutzt. Dieses Modul kennt nur noch seine eigenen
//! Nachrichten; NRC-Texte, Aufschub-Grenze und Sitzungsauswertung liegen in
//! [`crate::uds`]. Zwei Sendestellen hätten sich früher oder später
//! unterschiedlich verhalten, und kein Test hätte es gemerkt.

use std::fmt;

use crate::audit::{AuditKette, Ergebnis};
use crate::isotp::IsotpChannel;
use crate::transport::{Result as TransportResult, Transport, TransportError};
use crate::uds::{UdsClient, UdsFehler};

// Diese Namen standen einmal hier; sie kommen jetzt aus `uds`, damit es eine
// NRC-Tabelle und eine Aufschub-Grenze gibt. Sie bleiben unter dem alten Namen
// erreichbar (`sfd::nrc_text`), damit Aufrufer nichts ändern müssen.
pub use crate::uds::{
    ist_aufschub, nrc_text, pruefe_nrc, sitzung_anfrage, ANTWORT_NEGATIV, ANTWORT_SITZUNG,
    DIENST_SITZUNG, MAX_AUFSCHUB, NRC_RESPONSE_PENDING, P2_STERN_MS, SITZUNG_EXTENDED,
};

/// Dienst „ReadDataByIdentifier".
pub const DIENST_READ_DATA: u8 = 0x22;
/// Dienst „RoutineControl" — derselbe Wert wie [`crate::uds::DIENST_ROUTINE`].
pub const DIENST_ROUTINE: u8 = crate::uds::DIENST_ROUTINE;
/// Subfunktion `startRoutine` — derselbe Wert wie [`crate::uds::ROUTINE_START`].
pub const SUB_START: u8 = crate::uds::ROUTINE_START;
/// DID der SFD-Statusabfrage (`22 01 74`).
pub const STATUS_DID: [u8; 2] = [0x01, 0x74];
/// Routine-Kennung im vierten Byte der RoutineControl-Anfrage.
pub const ROUTINE_KENNUNG: u8 = 0xC0;
/// Schritt „Challenge lesen".
pub const MODUS_CHALLENGE: u8 = 0x04;
/// Schritt „Token senden".
pub const MODUS_ENTSPERREN: u8 = 0x08;
/// Schritt „wieder sperren".
pub const MODUS_SPERREN: u8 = 0x09;

/// Positive Antwort auf RoutineControl.
pub const ANTWORT_ROUTINE: u8 = crate::uds::ANTWORT_ROUTINE;

/// Dienstbezeichnung im Audit-Eintrag für das Entsperren.
///
/// Ausgeschrieben statt als Bytefolge: der Eintrag soll ohne Nachschlagen
/// lesbar sein, wenn er Wochen später in einem Ticket liegt. Der Token steht
/// bewusst **nicht** darin.
pub const DIENST_ENTSPERREN: &str = "SFD entsperren 31 01 C0 08";

/// Dienstbezeichnung im Audit-Eintrag für das Sperren.
pub const DIENST_SPERREN: &str = "SFD sperren 31 01 C0 09";
/// Positive Antwort auf ReadDataByIdentifier.
pub const ANTWORT_READ_DATA: u8 = 0x62;

/// Längster Token, der in eine ISO-TP-Nachricht passt.
///
/// ISO 15765-2 erlaubt 4095 Byte je Nachricht (12-Bit-Längenfeld). Davon gehen
/// vier Byte Kopf ab (`31 01 C0 08`) — mehr passt nicht in **eine** Nachricht,
/// und ein zweiter Block wäre ein anderes Protokoll.
pub const MAX_TOKEN: usize = 0x0FFF - 4;

/// Fehler einer SFD-Nachricht — vor dem Senden oder beim Auswerten.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SfdError {
    /// Das Steuergerät hat abgelehnt (`7F <Dienst> <NRC>`).
    Nrc { dienst: u8, code: u8 },
    /// Antwort gehört zu einem anderen Dienst.
    FalscherDienst { erwartet: u8, gesehen: u8 },
    /// Antwort gehört zu einem anderen Schritt der Routine.
    FalscherModus { erwartet: u8, gesehen: u8 },
    /// Antwort ist kürzer als ihr eigener Kopf.
    ZuKurz { gelesen: usize, mindestens: usize },
    /// Antwort ist länger als erlaubt.
    ZuLang { gelesen: usize, hoechstens: usize },
    /// Leerer Token — ein Senden wäre sinnlos.
    TokenLeer,
    /// Token passt nicht in eine ISO-TP-Nachricht.
    TokenZuLang { laenge: usize, hoechstens: usize },
    /// Rolle 0 ist reserviert.
    RolleUngueltig { rolle: u8 },
    /// Hex-Text enthält ein Zeichen, das keine Hexziffer ist.
    UngueltigesHexZeichen { stelle: usize },
    /// Hex-Text hat eine ungerade Zahl von Ziffern.
    UngeradeHexLaenge { laenge: usize },
}

impl fmt::Display for SfdError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SfdError::Nrc { dienst, code } => write!(
                f,
                "abgelehnt (Dienst 0x{dienst:02X}, NRC 0x{code:02X}: {})",
                nrc_text(*code)
            ),
            SfdError::FalscherDienst { erwartet, gesehen } => write!(
                f,
                "unerwarteter Dienst: erwartet 0x{erwartet:02X}, gesehen 0x{gesehen:02X}"
            ),
            SfdError::FalscherModus { erwartet, gesehen } => write!(
                f,
                "unerwarteter Schritt: erwartet 0x{erwartet:02X}, gesehen 0x{gesehen:02X}"
            ),
            SfdError::ZuKurz {
                gelesen,
                mindestens,
            } => write!(
                f,
                "Antwort zu kurz: {gelesen} Byte, mindestens {mindestens} erwartet"
            ),
            SfdError::ZuLang {
                gelesen,
                hoechstens,
            } => write!(
                f,
                "Antwort zu lang: {gelesen} Byte, höchstens {hoechstens} erlaubt"
            ),
            SfdError::TokenLeer => write!(f, "Token ist leer"),
            SfdError::TokenZuLang { laenge, hoechstens } => write!(
                f,
                "Token zu lang: {laenge} Byte, höchstens {hoechstens} möglich"
            ),
            SfdError::RolleUngueltig { rolle } => {
                write!(f, "Rolle 0x{rolle:02X} ist nicht zulässig")
            }
            SfdError::UngueltigesHexZeichen { stelle } => {
                write!(f, "kein Hexzeichen an Stelle {stelle}")
            }
            SfdError::UngeradeHexLaenge { laenge } => write!(
                f,
                "Hex-Text hat {laenge} Ziffern — eine ungerade Zahl ergibt kein Byte"
            ),
        }
    }
}

impl std::error::Error for SfdError {}

/// `22 01 74` — Status des SFD-Schutzes lesen.
pub fn status_anfrage() -> Vec<u8> {
    vec![DIENST_READ_DATA, STATUS_DID[0], STATUS_DID[1]]
}

/// Die 16-Bit-Routinenkennung zu einem SFD-Schritt.
///
/// `31 01 C0 04` ist Routine `0xC004`, nicht „Routine `C0`, Modus `04`" — die
/// Kennung ist zwei Byte breit. Diese Funktion ist die einzige Stelle, die
/// diese Zusammensetzung kennt.
pub const fn routine_kennung(modus: u8) -> u16 {
    u16::from_be_bytes([ROUTINE_KENNUNG, modus])
}

/// `31 01 C0 04 <Rolle>` — Challenge anfordern.
///
/// Die Rolle ist der einzige Parameter; sie kommt aus der Diagnosebeschreibung
/// des Fahrzeugs und wird hier nur auf „nicht 0" geprüft.
pub fn challenge_anfrage(rolle: u8) -> std::result::Result<Vec<u8>, SfdError> {
    if rolle == 0 {
        return Err(SfdError::RolleUngueltig { rolle });
    }
    Ok(crate::uds::routine_start_anfrage(
        routine_kennung(MODUS_CHALLENGE),
        &[rolle],
    ))
}

/// `31 01 C0 08 <Token>` — Token senden.
///
/// Länge wird hier geprüft, nicht im Aufrufer: ein abgeschnittener Token führt
/// am Fahrzeug zu `NRC 0x13` oder — schlimmer — zu einem halb entsperrten
/// Steuergerät.
pub fn token_anfrage(token: &[u8]) -> std::result::Result<Vec<u8>, SfdError> {
    if token.is_empty() {
        return Err(SfdError::TokenLeer);
    }
    if token.len() > MAX_TOKEN {
        return Err(SfdError::TokenZuLang {
            laenge: token.len(),
            hoechstens: MAX_TOKEN,
        });
    }
    Ok(crate::uds::routine_start_anfrage(
        routine_kennung(MODUS_ENTSPERREN),
        token,
    ))
}

/// `31 01 C0 09` — wieder sperren.
pub fn sperren_anfrage() -> Vec<u8> {
    crate::uds::routine_start_anfrage(routine_kennung(MODUS_SPERREN), &[])
}

/// `10 03` — in die Extended Session wechseln.
///
/// Viele Steuergeräte beantworten `31 01 C0 04` und `31 01 C0 08` nur in dieser
/// Sitzung; in der Default-Session kommt `7F 31 7E` oder `7F 31 7F`. Der
/// Sitzungswechsel ist deshalb ein eigener Schritt und keine stille Annahme:
/// ob er nötig war, sagt das Steuergerät im NRC.
pub fn extended_anfrage() -> Vec<u8> {
    sitzung_anfrage(SITZUNG_EXTENDED)
}

/// Wertet `50 <Sitzung>` aus.
///
/// Die Prüfung selbst liegt in [`crate::uds::parse_sitzung`]; hier bleibt nur
/// die Übersetzung in die SFD-Sprache. Für SFD heißt eine Subfunktion
/// „Schritt“ (`FalscherModus`), und die Fristen `P2`/`P2*` aus der Antwort
/// braucht dieser Ablauf nicht — er wartet ohnehin auf den Abschluss.
pub fn parse_sitzung(antwort: &[u8], sitzung: u8) -> std::result::Result<(), SfdError> {
    match crate::uds::parse_sitzung(antwort, sitzung) {
        Ok(_) => Ok(()),
        Err(UdsFehler::Nrc { dienst, code }) => Err(SfdError::Nrc { dienst, code }),
        Err(UdsFehler::FalscherDienst { erwartet, gesehen }) => {
            Err(SfdError::FalscherDienst { erwartet, gesehen })
        }
        Err(UdsFehler::FalscheSubfunktion { erwartet, gesehen }) => {
            Err(SfdError::FalscherModus { erwartet, gesehen })
        }
        Err(UdsFehler::ZuKurz {
            gelesen,
            mindestens,
        }) => Err(SfdError::ZuKurz {
            gelesen,
            mindestens,
        }),
        // Eine Sitzungsantwort trägt keine Kennung und keine DTC-Liste, und
        // gesendet wird hier nichts — diese Fälle kann der Parser nicht
        // liefern. Als Dienstfehler gemeldet, damit nichts als Erfolg durchgeht.
        Err(_) => Err(SfdError::FalscherDienst {
            erwartet: ANTWORT_SITZUNG,
            gesehen: antwort.first().copied().unwrap_or(0),
        }),
    }
}

/// Rohe Statusantwort (`62 01 74 …`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SfdStatus {
    /// Bytes hinter dem Echo — die Statusinformation des Steuergeräts.
    pub daten: Vec<u8>,
    /// Die **ganze** Antwort, `62 01 74 …` eingeschlossen.
    ///
    /// Der Grund ist die Werkstatt: SFD1- und SFD2-Steuergeräte liefern auf
    /// `22 01 74` unterschiedlich lange Antworten, und eine Deutung, die davon
    /// ausgeht, kann falsch liegen. Die Rohantwort geht deshalb immer mit —
    /// auch dann, wenn das Layout nicht gedeutet werden kann.
    pub roh: Vec<u8>,
}

/// Wertet `62 01 74 …` aus.
pub fn parse_status(antwort: &[u8]) -> std::result::Result<SfdStatus, SfdError> {
    if let Some((dienst, code)) = pruefe_nrc(antwort) {
        return Err(SfdError::Nrc { dienst, code });
    }
    const KOPF: usize = 3;
    if antwort.len() < KOPF {
        return Err(SfdError::ZuKurz {
            gelesen: antwort.len(),
            mindestens: KOPF,
        });
    }
    if antwort[0] != ANTWORT_READ_DATA {
        return Err(SfdError::FalscherDienst {
            erwartet: ANTWORT_READ_DATA,
            gesehen: antwort[0],
        });
    }
    if antwort[1..3] != STATUS_DID {
        return Err(SfdError::FalscherModus {
            erwartet: STATUS_DID[1],
            gesehen: antwort[2],
        });
    }
    Ok(SfdStatus {
        daten: antwort[KOPF..].to_vec(),
        roh: antwort.to_vec(),
    })
}

/// Wertet `71 01 C0 04 <Challenge>` aus und gibt die Challenge zurück.
///
/// Eine Response ohne Challenge-Bytes (`71 01 C0 04`, 4 Byte) ist ungültig:
/// das Steuergerät hätte dann nichts geliefert, womit sich ein Token bilden
/// ließe.
pub fn parse_challenge(antwort: &[u8]) -> std::result::Result<Vec<u8>, SfdError> {
    let nutzlast = parse_routine(antwort, MODUS_CHALLENGE)?;
    if nutzlast.is_empty() {
        return Err(SfdError::ZuKurz {
            gelesen: antwort.len(),
            mindestens: 5,
        });
    }
    Ok(nutzlast.to_vec())
}

/// Wertet die Antwort auf das Senden des Tokens aus (`71 01 C0 08`).
pub fn parse_entsperrt(antwort: &[u8]) -> std::result::Result<(), SfdError> {
    parse_routine(antwort, MODUS_ENTSPERREN).map(|_| ())
}

/// Wertet die Antwort auf das Sperren aus (`71 01 C0 09`).
pub fn parse_gesperrt(antwort: &[u8]) -> std::result::Result<(), SfdError> {
    parse_routine(antwort, MODUS_SPERREN).map(|_| ())
}

/// Gemeinsamer Kopf `71 01 C0 <Modus>` — liefert die Nutzlast dahinter.
///
/// Geprüft wird in [`crate::uds::routine_kopf`], also an derselben Stelle wie
/// bei jeder anderen Routine. Hier bleibt die Übersetzung in die SFD-Sprache:
/// für SFD heißt eine Subfunktion „Schritt" (`FalscherModus`), weil die App
/// so schon seit dem ersten Ausbau meldet.
fn parse_routine(antwort: &[u8], modus: u8) -> std::result::Result<&[u8], SfdError> {
    match crate::uds::routine_kopf(antwort, SUB_START, routine_kennung(modus)) {
        Ok(nutzlast) => Ok(nutzlast),
        Err(UdsFehler::Nrc { dienst, code }) => Err(SfdError::Nrc { dienst, code }),
        Err(UdsFehler::FalscherDienst { erwartet, gesehen }) => {
            Err(SfdError::FalscherDienst { erwartet, gesehen })
        }
        Err(UdsFehler::FalscheSubfunktion { erwartet, gesehen }) => {
            Err(SfdError::FalscherModus { erwartet, gesehen })
        }
        Err(UdsFehler::FalscheRoutine { gesehen, .. }) => Err(SfdError::FalscherModus {
            erwartet: modus,
            gesehen: gesehen[1],
        }),
        Err(UdsFehler::ZuKurz {
            gelesen,
            mindestens,
        }) => Err(SfdError::ZuKurz {
            gelesen,
            mindestens,
        }),
        // Kopf und Aufschub sind alles, was `runde` liefert; die übrigen Fälle
        // kann dieser Parser nicht erzeugen. Gemeldet wird trotzdem — nichts
        // darf als Erfolg durchgehen.
        Err(_) => Err(SfdError::FalscherDienst {
            erwartet: ANTWORT_ROUTINE,
            gesehen: antwort.first().copied().unwrap_or(0),
        }),
    }
}

/// Hex ohne Trenner, Großbuchstaben — so, wie der Token kopiert wird.
pub fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02X}"));
    }
    s
}

/// Wertet Hex-Text aus einem Portal aus.
///
/// Trenner (Leerzeichen, Zeilenumbrüche, `:` und `-`) sind erlaubt, weil kopierte
/// Token je nach Quelle gruppiert sind. Alles andere muss eine Hexziffer sein —
/// ein stillschweigend übersprungenes Zeichen würde einen falschen Token senden.
pub fn aus_hex(text: &str) -> std::result::Result<Vec<u8>, SfdError> {
    let mut ziffern: Vec<u8> = Vec::with_capacity(text.len());
    for (stelle, zeichen) in text.chars().enumerate() {
        if zeichen.is_ascii_whitespace() || zeichen == ':' || zeichen == '-' {
            continue;
        }
        let wert = match zeichen {
            '0'..='9' => zeichen as u8 - b'0',
            'a'..='f' => zeichen as u8 - b'a' + 10,
            'A'..='F' => zeichen as u8 - b'A' + 10,
            _ => return Err(SfdError::UngueltigesHexZeichen { stelle }),
        };
        ziffern.push(wert);
    }
    if ziffern.len() % 2 != 0 {
        return Err(SfdError::UngeradeHexLaenge {
            laenge: ziffern.len(),
        });
    }
    Ok(ziffern
        .chunks_exact(2)
        .map(|paar| (paar[0] << 4) | paar[1])
        .collect())
}

/// Fehler eines SFD-Ablaufs: Transport oder Protokoll.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SfdFehler {
    Transport(TransportError),
    Sfd(SfdError),
}

impl fmt::Display for SfdFehler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SfdFehler::Transport(e) => write!(f, "{e}"),
            SfdFehler::Sfd(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for SfdFehler {}

impl From<TransportError> for SfdFehler {
    fn from(e: TransportError) -> Self {
        SfdFehler::Transport(e)
    }
}

impl From<SfdError> for SfdFehler {
    fn from(e: SfdError) -> Self {
        SfdFehler::Sfd(e)
    }
}

/// Übersetzt einen Fehler aus [`crate::uds`] in die SFD-Sprache.
///
/// Nötig, weil der Ablauf unten nur noch `uds::runde()` aufruft: ein Timeout
/// bleibt ein Transportfehler, ein NRC behält Dienst und Code, und eine
/// Subfunktion heißt bei SFD „Schritt".
impl From<UdsFehler> for SfdFehler {
    fn from(e: UdsFehler) -> Self {
        match e {
            UdsFehler::Transport(t) => SfdFehler::Transport(t),
            UdsFehler::Nrc { dienst, code } => SfdFehler::Sfd(SfdError::Nrc { dienst, code }),
            UdsFehler::FalscherDienst { erwartet, gesehen } => {
                SfdFehler::Sfd(SfdError::FalscherDienst { erwartet, gesehen })
            }
            UdsFehler::FalscheSubfunktion { erwartet, gesehen } => {
                SfdFehler::Sfd(SfdError::FalscherModus { erwartet, gesehen })
            }
            // Kann `runde` nicht liefern — die Übersetzung existiert trotzdem,
            // damit der Fehler nicht in einem `_`-Zweig als etwas anderes
            // ausgegeben wird. Die Kennung meldet sich als falscher Schritt.
            UdsFehler::FalscheRoutine { gesehen, .. } => SfdFehler::Sfd(SfdError::FalscherModus {
                erwartet: ROUTINE_KENNUNG,
                gesehen: gesehen[1],
            }),
            UdsFehler::ZuKurz {
                gelesen,
                mindestens,
            } => SfdFehler::Sfd(SfdError::ZuKurz {
                gelesen,
                mindestens,
            }),
            // `runde` wertet nur Kopf und Aufschub aus; eine Kennung oder eine
            // DTC-Liste kann es nicht liefern. Landet so ein Fehler hier, ist
            // die Verdrahtung falsch — gemeldet wird er als Protokollfehler,
            // nie als Erfolg.
            UdsFehler::FalscheDid { .. }
            | UdsFehler::DtcRest { .. }
            | UdsFehler::LeererSchluessel { .. } => SfdFehler::Sfd(SfdError::FalscherDienst {
                erwartet: ANTWORT_ROUTINE,
                gesehen: 0x00,
            }),
        }
    }
}

/// SFD-Ablauf über einen ISO-TP-Kanal.
///
/// Die Anfragen sind reine Bytefolgen; dieser Typ erledigt Senden, Warten und
/// Auswerten inklusive der Aufschübe (`NRC 0x78`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SfdClient {
    /// Der allgemeine Client — er besitzt den ISO-TP-Kanal und die einzige
    /// Sendestelle. `SfdClient` fügt nur die SFD-Nachrichten hinzu.
    uds: UdsClient,
}

impl Default for SfdClient {
    fn default() -> Self {
        Self::new()
    }
}

impl SfdClient {
    pub const fn new() -> Self {
        SfdClient {
            uds: UdsClient::new(),
        }
    }

    pub const fn with_ids(req_id: u32, rsp_id: u32) -> Self {
        SfdClient {
            uds: UdsClient::with_ids(req_id, rsp_id),
        }
    }

    /// `22 01 74` — Status lesen.
    pub fn status<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> std::result::Result<SfdStatus, SfdFehler> {
        let antwort = self.uds.runde(t, &status_anfrage(), timeout_ms)?;
        Ok(parse_status(&antwort)?)
    }

    /// Challenge anfordern und als Bytes zurückgeben.
    pub fn challenge<T: Transport>(
        &self,
        t: &mut T,
        rolle: u8,
        timeout_ms: u32,
    ) -> std::result::Result<Vec<u8>, SfdFehler> {
        let anfrage = challenge_anfrage(rolle)?;
        let antwort = self.uds.runde(t, &anfrage, timeout_ms)?;
        Ok(parse_challenge(&antwort)?)
    }

    /// Token senden (entsperren).
    pub fn entsperren<T: Transport>(
        &self,
        t: &mut T,
        token: &[u8],
        timeout_ms: u32,
    ) -> std::result::Result<(), SfdFehler> {
        let anfrage = token_anfrage(token)?;
        let antwort = self.uds.runde(t, &anfrage, timeout_ms)?;
        Ok(parse_entsperrt(&antwort)?)
    }

    /// Wieder sperren.
    pub fn sperren<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> std::result::Result<(), SfdFehler> {
        let antwort = self.uds.runde(t, &sperren_anfrage(), timeout_ms)?;
        Ok(parse_gesperrt(&antwort)?)
    }

    /// Entsperren **und** Eintrag in der Audit-Kette.
    ///
    /// Der Eintrag enthält den Token nicht: die Kette ist ein Nachweis, den man
    /// weiterreicht, und ein Token darin wäre ein weitergegebenes Geheimnis.
    /// Steht nur der Dienst drin, belegt der Eintrag den Vorgang, ohne den
    /// Schlüssel zu verraten.
    ///
    /// Auch der Fehlschlag wird protokolliert — ein abgelehnter Unlock ist
    /// genau der Vorgang, den man später erklärt haben will.
    pub fn entsperren_protokolliert<T: Transport>(
        &self,
        t: &mut T,
        token: &[u8],
        timeout_ms: u32,
        vin: &str,
        kette: &mut AuditKette,
        zeit_ms: u64,
    ) -> std::result::Result<(), SfdFehler> {
        let ergebnis = self.entsperren(t, token, timeout_ms);
        self.protokolliere(kette, zeit_ms, vin, DIENST_ENTSPERREN, &ergebnis);
        ergebnis
    }

    /// Sperren **und** Eintrag in der Audit-Kette.
    pub fn sperren_protokolliert<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
        vin: &str,
        kette: &mut AuditKette,
        zeit_ms: u64,
    ) -> std::result::Result<(), SfdFehler> {
        let ergebnis = self.sperren(t, timeout_ms);
        self.protokolliere(kette, zeit_ms, vin, DIENST_SPERREN, &ergebnis);
        ergebnis
    }

    /// Hängt den Ausgang eines SFD-Vorgangs an die Kette.
    ///
    /// Das Steuergerät ist die Anfrage-Kennung des Kanals — mehr weiß der Kern
    /// an dieser Stelle nicht, und eine geratene Kennung wäre schlimmer als
    /// keine.
    fn protokolliere(
        &self,
        kette: &mut AuditKette,
        zeit_ms: u64,
        vin: &str,
        dienst: &str,
        ergebnis: &std::result::Result<(), SfdFehler>,
    ) {
        let eintrag = match ergebnis {
            Ok(()) => Ergebnis::Erfolg,
            Err(e) => Ergebnis::Fehler {
                grund: e.to_string(),
            },
        };
        kette.anhaengen(zeit_ms, vin, self.iso().req_id(), dienst, eintrag);
    }

    /// `10 03` — Extended Session starten.
    pub fn extended<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> std::result::Result<(), SfdFehler> {
        self.sitzung(t, SITZUNG_EXTENDED, timeout_ms)
    }

    /// Sitzungswechsel mit beliebiger Subfunktion (`10 xx`).
    pub fn sitzung<T: Transport>(
        &self,
        t: &mut T,
        sitzung: u8,
        timeout_ms: u32,
    ) -> std::result::Result<(), SfdFehler> {
        let antwort = self.uds.runde(t, &sitzung_anfrage(sitzung), timeout_ms)?;
        Ok(parse_sitzung(&antwort, sitzung)?)
    }

    pub fn iso(&self) -> &IsotpChannel {
        self.uds.iso()
    }
}

/// Prüft eine Antwort gegen die erwartete Länge und liefert sie zurück.
///
/// Nur für Aufrufer gedacht, die eine ganze Nachricht schon haben (z. B. nach
/// `recv`); die Parser oben prüfen selbst.
pub fn pruefe_laenge(antwort: &[u8], hoechstens: usize) -> std::result::Result<(), SfdError> {
    if antwort.len() > hoechstens {
        return Err(SfdError::ZuLang {
            gelesen: antwort.len(),
            hoechstens,
        });
    }
    Ok(())
}

/// Wandelt das Ergebnis eines ISO-TP-Empfangs in einen SFD-Fehler um.
///
/// Hilft Aufrufern, die selbst senden und nur auswerten wollen.
pub fn aus_transport(
    ergebnis: TransportResult<Vec<u8>>,
) -> std::result::Result<Vec<u8>, SfdFehler> {
    Ok(ergebnis?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::loopback::Loopback;
    use crate::transport::CanFrame;

    /// Baut einen Single Frame um eine UDS-Antwort (höchstens sieben Byte).
    fn antwort(bytes: &[u8]) -> CanFrame {
        let mut f = Vec::with_capacity(bytes.len() + 1);
        f.push(bytes.len() as u8);
        f.extend_from_slice(bytes);
        CanFrame::new(crate::isotp::RSP_ID, &f)
    }

    fn flow_control() -> CanFrame {
        CanFrame::new(crate::isotp::RSP_ID, &[0x30, 0x00, 0x00])
    }

    #[test]
    fn status_anfrage_ist_22_01_74() {
        assert_eq!(status_anfrage(), vec![0x22, 0x01, 0x74]);
    }

    #[test]
    fn challenge_anfrage_traegt_die_rolle() {
        assert_eq!(
            challenge_anfrage(0x05).unwrap(),
            vec![0x31, 0x01, 0xC0, 0x04, 0x05]
        );
    }

    #[test]
    fn routine_kennung_legt_den_schritt_in_das_niedrige_byte() {
        assert_eq!(routine_kennung(MODUS_CHALLENGE).to_be_bytes(), [0xC0, 0x04]);
        assert_eq!(
            routine_kennung(MODUS_ENTSPERREN).to_be_bytes(),
            [0xC0, 0x08]
        );
        // Und die Anfrage kommt aus dem allgemeinen Baukasten, nicht aus einer
        // zweiten Bytefolge hier.
        assert_eq!(
            sperren_anfrage(),
            crate::uds::routine_start_anfrage(routine_kennung(MODUS_SPERREN), &[])
        );
    }

    #[test]
    fn rolle_null_wird_abgelehnt() {
        assert_eq!(
            challenge_anfrage(0).unwrap_err(),
            SfdError::RolleUngueltig { rolle: 0 }
        );
    }

    #[test]
    fn token_anfrage_hat_den_kopf_31_01_c0_08() {
        let anfrage = token_anfrage(&[0xDE, 0xAD]).unwrap();
        assert_eq!(anfrage, vec![0x31, 0x01, 0xC0, 0x08, 0xDE, 0xAD]);
    }

    #[test]
    fn leerer_token_wird_abgelehnt() {
        assert_eq!(token_anfrage(&[]).unwrap_err(), SfdError::TokenLeer);
    }

    #[test]
    fn zu_langer_token_wird_abgelehnt() {
        let zu_lang = vec![0xAA; MAX_TOKEN + 1];
        assert_eq!(
            token_anfrage(&zu_lang).unwrap_err(),
            SfdError::TokenZuLang {
                laenge: MAX_TOKEN + 1,
                hoechstens: MAX_TOKEN
            }
        );
    }

    #[test]
    fn token_an_der_grenze_passt() {
        let grenze = vec![0xAA; MAX_TOKEN];
        let anfrage = token_anfrage(&grenze).unwrap();
        assert_eq!(anfrage.len(), 0x0FFF);
    }

    #[test]
    fn sperren_anfrage_ist_31_01_c0_09() {
        assert_eq!(sperren_anfrage(), vec![0x31, 0x01, 0xC0, 0x09]);
    }

    #[test]
    fn nrc_wird_erkannt_und_benannt() {
        assert_eq!(pruefe_nrc(&[0x7F, 0x31, 0x22]), Some((0x31, 0x22)));
        assert_eq!(pruefe_nrc(&[0x71, 0x01, 0xC0, 0x04, 0x01]), None);
        assert_eq!(pruefe_nrc(&[0x7F, 0x31]), None, "abgeschnittene Antwort");
        assert!(SfdError::Nrc {
            dienst: 0x31,
            code: 0x22
        }
        .to_string()
        .contains("conditionsNotCorrect"));
    }

    #[test]
    fn status_wird_ausgewertet() {
        let status = parse_status(&[0x62, 0x01, 0x74, 0x01]).unwrap();
        assert_eq!(status.daten, vec![0x01]);
    }

    #[test]
    fn status_ohne_daten_ist_gueltig() {
        assert_eq!(
            parse_status(&[0x62, 0x01, 0x74]).unwrap(),
            SfdStatus {
                daten: vec![],
                roh: vec![0x62, 0x01, 0x74]
            }
        );
    }

    #[test]
    fn status_roh_enthaelt_die_ganze_antwort() {
        let status = parse_status(&[0x62, 0x01, 0x74, 0x01, 0x02]).unwrap();
        assert_eq!(status.daten, vec![0x01, 0x02]);
        assert_eq!(status.roh, vec![0x62, 0x01, 0x74, 0x01, 0x02]);
    }

    #[test]
    fn status_mit_abweichendem_layout_wird_nicht_abgelehnt() {
        // SFD1 und SFD2 antworten unterschiedlich lang. Ein anderes Layout ist
        // kein Grund aufzugeben: gedeutet wird, was passt, sonst bleiben die
        // Rohbytes — beides entscheidet die App, nicht der Parser.
        let lang = parse_status(&[0x62, 0x01, 0x74, 0x01, 0x02, 0x00, 0x1E, 0xAA]).unwrap();
        assert_eq!(lang.daten.len(), 5);
        let kurz = parse_status(&[0x62, 0x01, 0x74, 0x01]).unwrap();
        assert_eq!(kurz.daten, vec![0x01]);
    }

    #[test]
    fn extended_anfrage_ist_10_03() {
        assert_eq!(extended_anfrage(), vec![0x10, 0x03]);
        assert_eq!(sitzung_anfrage(0x02), vec![0x10, 0x02]);
    }

    #[test]
    fn sitzungswechsel_wird_ausgewertet() {
        assert_eq!(parse_sitzung(&[0x50, 0x03], 0x03), Ok(()));
        // Zusätzliche Bytes (P2/P2*-Werte) stören nicht.
        assert_eq!(
            parse_sitzung(&[0x50, 0x03, 0x00, 0x32, 0x01, 0xF4], 0x03),
            Ok(())
        );
    }

    #[test]
    fn sitzung_nrc_7e_wird_benannt() {
        assert_eq!(
            parse_sitzung(&[0x7F, 0x10, 0x7E], 0x03).unwrap_err(),
            SfdError::Nrc {
                dienst: 0x10,
                code: 0x7E
            }
        );
    }

    #[test]
    fn sitzung_mit_falschem_dienst_wird_abgelehnt() {
        assert_eq!(
            parse_sitzung(&[0x51, 0x03], 0x03).unwrap_err(),
            SfdError::FalscherDienst {
                erwartet: 0x50,
                gesehen: 0x51
            }
        );
    }

    #[test]
    fn sitzung_mit_falscher_subfunktion_wird_abgelehnt() {
        assert_eq!(
            parse_sitzung(&[0x50, 0x02], 0x03).unwrap_err(),
            SfdError::FalscherModus {
                erwartet: 0x03,
                gesehen: 0x02
            }
        );
    }

    #[test]
    fn sitzung_zu_kurz_wird_abgelehnt() {
        assert_eq!(
            parse_sitzung(&[0x50], 0x03).unwrap_err(),
            SfdError::ZuKurz {
                gelesen: 1,
                mindestens: 2
            }
        );
    }

    #[test]
    fn status_mit_falschem_dienst_wird_abgelehnt() {
        assert_eq!(
            parse_status(&[0x71, 0x01, 0x74, 0x00]).unwrap_err(),
            SfdError::FalscherDienst {
                erwartet: 0x62,
                gesehen: 0x71
            }
        );
    }

    #[test]
    fn status_zu_kurz_wird_abgelehnt() {
        assert_eq!(
            parse_status(&[0x62, 0x01]).unwrap_err(),
            SfdError::ZuKurz {
                gelesen: 2,
                mindestens: 3
            }
        );
    }

    #[test]
    fn status_mit_nrc_wird_abgelehnt() {
        assert_eq!(
            parse_status(&[0x7F, 0x22, 0x31]).unwrap_err(),
            SfdError::Nrc {
                dienst: 0x22,
                code: 0x31
            }
        );
    }

    #[test]
    fn challenge_wird_ausgewertet() {
        let rsp = [0x71, 0x01, 0xC0, 0x04, 0xA1, 0xB2, 0xC3];
        assert_eq!(parse_challenge(&rsp).unwrap(), vec![0xA1, 0xB2, 0xC3]);
    }

    #[test]
    fn challenge_ohne_nutzlast_wird_abgelehnt() {
        let rsp = [0x71, 0x01, 0xC0, 0x04];
        assert_eq!(
            parse_challenge(&rsp).unwrap_err(),
            SfdError::ZuKurz {
                gelesen: 4,
                mindestens: 5
            }
        );
    }

    #[test]
    fn challenge_mit_falschem_modus_wird_abgelehnt() {
        let rsp = [0x71, 0x01, 0xC0, 0x08, 0x01];
        assert_eq!(
            parse_challenge(&rsp).unwrap_err(),
            SfdError::FalscherModus {
                erwartet: 0x04,
                gesehen: 0x08
            }
        );
    }

    #[test]
    fn abgeschnittene_challenge_wird_abgelehnt() {
        assert_eq!(
            parse_challenge(&[0x71, 0x01]).unwrap_err(),
            SfdError::ZuKurz {
                gelesen: 2,
                mindestens: 4
            }
        );
    }

    #[test]
    fn entsperrt_und_gesperrt_werden_erkannt() {
        assert_eq!(parse_entsperrt(&[0x71, 0x01, 0xC0, 0x08]).unwrap(), ());
        assert_eq!(parse_gesperrt(&[0x71, 0x01, 0xC0, 0x09]).unwrap(), ());
        assert_eq!(
            parse_entsperrt(&[0x71, 0x01, 0xC0, 0x09]).unwrap_err(),
            SfdError::FalscherModus {
                erwartet: 0x08,
                gesehen: 0x09
            }
        );
    }

    #[test]
    fn falscher_invalid_token_nrc_wird_benannt() {
        let err = parse_entsperrt(&[0x7F, 0x31, 0x35]).unwrap_err();
        assert_eq!(
            err,
            SfdError::Nrc {
                dienst: 0x31,
                code: 0x35
            }
        );
        assert!(err.to_string().contains("invalidKey"));
    }

    #[test]
    fn hex_und_zurueck() {
        assert_eq!(hex(&[0x00, 0xFF, 0xA1]), "00FFA1");
        assert_eq!(aus_hex("00 ff:a1").unwrap(), vec![0x00, 0xFF, 0xA1]);
        assert_eq!(aus_hex("").unwrap(), Vec::<u8>::new());
    }

    #[test]
    fn ungerade_hex_laenge_wird_abgelehnt() {
        assert_eq!(
            aus_hex("A1B").unwrap_err(),
            SfdError::UngeradeHexLaenge { laenge: 3 }
        );
    }

    #[test]
    fn falsches_hexzeichen_wird_abgelehnt() {
        assert_eq!(
            aus_hex("A1G2").unwrap_err(),
            SfdError::UngueltigesHexZeichen { stelle: 2 }
        );
    }

    #[test]
    fn status_am_fahrzeug_simuliert() {
        let mut t = Loopback::new(vec![antwort(&[0x62, 0x01, 0x74, 0x00])]);
        let status = SfdClient::new().status(&mut t, 100).unwrap();
        assert_eq!(status.daten, vec![0x00]);
        assert_eq!(&t.sent()[0].data[..4], &[0x03, 0x22, 0x01, 0x74]);
    }

    #[test]
    fn challenge_am_fahrzeug_simuliert() {
        // Sieben Byte passen in einen Single Frame: vier Byte Kopf + Challenge.
        let mut t = Loopback::new(vec![antwort(&[0x71, 0x01, 0xC0, 0x04, 0x11, 0x22, 0x33])]);
        let challenge = SfdClient::new().challenge(&mut t, 0x01, 100).unwrap();
        assert_eq!(challenge, vec![0x11, 0x22, 0x33]);
    }

    #[test]
    fn langer_token_geht_als_multi_frame_raus() {
        // 320 Byte Token: First Frame, Flow Control, 45 Consecutive Frames.
        let token = vec![0x5A; 320];
        let mut t = Loopback::new(vec![flow_control(), antwort(&[0x71, 0x01, 0xC0, 0x08])]);
        SfdClient::new().entsperren(&mut t, &token, 100).unwrap();

        let gesendet = t.sent();
        assert_eq!(gesendet[0].data[0] & 0xF0, 0x10, "First Frame");
        assert_eq!(
            ((gesendet[0].data[0] as usize & 0x0F) << 8) | gesendet[0].data[1] as usize,
            4 + token.len()
        );
        // 6 Byte im FF, danach 45 CF mit je 7 Byte = 315 → 321 ≠ 324? Prüfen:
        // 324 - 6 = 318 Byte → 45 CF (44 volle + 1 mit 3 Byte ... 44*7=308, Rest 10 → 2 CF)
        let cf = &gesendet[1..];
        assert_eq!(cf[0].data[0] & 0xF0, 0x20);
        assert_eq!(cf[0].data[0] & 0x0F, 1, "Sequenz beginnt bei 1");
        assert_eq!(cf[15].data[0] & 0x0F, 0, "Sequenz läuft bei 15 auf 0 über");
        // Nutzlast vollständig und in der richtigen Reihenfolge
        let mut zusammen = Vec::new();
        zusammen.extend_from_slice(&gesendet[0].data[2..8]);
        for f in cf {
            let n = (4 + token.len() - zusammen.len()).min(7);
            zusammen.extend_from_slice(&f.data[1..1 + n]);
        }
        // Auf der Leitung steht die ganze Anfrage: Kopf `31 01 C0 08` + Token.
        assert_eq!(zusammen, token_anfrage(&token).unwrap());
    }

    #[test]
    fn aufschub_wird_abgewartet() {
        let mut t = Loopback::new(vec![
            antwort(&[0x7F, 0x31, 0x78]),
            antwort(&[0x71, 0x01, 0xC0, 0x08]),
        ]);
        SfdClient::new()
            .entsperren(&mut t, &[0x01, 0x02], 100)
            .unwrap();
        // Genau eine Anfrage: nach dem Aufschub wird nicht erneut gesendet.
        assert_eq!(t.sent().len(), 1);
    }

    #[test]
    fn dauerhafter_aufschub_bricht_ab() {
        let mut t = Loopback::new(vec![
            antwort(&[0x7F, 0x31, 0x78]),
            antwort(&[0x7F, 0x31, 0x78]),
            antwort(&[0x7F, 0x31, 0x78]),
            antwort(&[0x7F, 0x31, 0x78]),
        ]);
        let err = SfdClient::new()
            .entsperren(&mut t, &[0x01, 0x02], 100)
            .unwrap_err();
        assert!(matches!(
            err,
            SfdFehler::Sfd(SfdError::Nrc { code: 0x78, .. })
        ));
    }

    #[test]
    fn ablehnung_kommt_als_nrc_zurueck() {
        let mut t = Loopback::new(vec![antwort(&[0x7F, 0x31, 0x36])]);
        let err = SfdClient::new()
            .entsperren(&mut t, &[0x01, 0x02], 100)
            .unwrap_err();
        assert!(err.to_string().contains("exceedNumberOfAttempts"), "{err}");
    }

    #[test]
    fn zeitueberschreitung_bleibt_zeitueberschreitung() {
        let mut t = Loopback::empty();
        let err = SfdClient::new().status(&mut t, 10).unwrap_err();
        assert_eq!(err, SfdFehler::Transport(TransportError::Timeout));
    }

    #[test]
    fn entsperren_landet_als_eintrag_in_der_kette() {
        let mut t = Loopback::new(vec![antwort(&[0x71, 0x01, 0xC0, 0x08])]);
        let mut kette = AuditKette::neu();

        SfdClient::new()
            .entsperren_protokolliert(
                &mut t,
                &[0xDE, 0xAD],
                100,
                "WVWZZZ1JZXW000001",
                &mut kette,
                1_700_000_000_000,
            )
            .unwrap();

        let eintraege = kette.eintraege();
        assert_eq!(eintraege.len(), 1);
        assert_eq!(eintraege[0].dienst, DIENST_ENTSPERREN);
        assert_eq!(eintraege[0].steuergeraet, crate::isotp::REQ_ID);
        assert_eq!(eintraege[0].zeit_ms, 1_700_000_000_000);
        assert!(matches!(eintraege[0].ergebnis, Ergebnis::Erfolg));
        assert!(kette.pruefen().is_ok());

        // Der Nachweis belegt den Vorgang, ohne den Schlüssel zu verraten.
        let json = kette.als_json();
        assert!(
            !json.contains("DEAD"),
            "Token darf nicht im Nachweis stehen"
        );
        assert!(json.contains("31 01 C0 08"), "{json}");
    }

    #[test]
    fn abgelehntes_entsperren_steht_als_fehler_in_der_kette() {
        let mut t = Loopback::new(vec![antwort(&[0x7F, 0x31, 0x35])]);
        let mut kette = AuditKette::neu();

        let fehler = SfdClient::new()
            .entsperren_protokolliert(&mut t, &[0x01, 0x02], 100, "VIN", &mut kette, 5)
            .unwrap_err();
        assert!(fehler.to_string().contains("invalidKey"), "{fehler}");

        let eintraege = kette.eintraege();
        assert_eq!(eintraege.len(), 1, "auch die Ablehnung wird protokolliert");
        match &eintraege[0].ergebnis {
            Ergebnis::Fehler { grund } => assert!(grund.contains("invalidKey"), "{grund}"),
            Ergebnis::Erfolg => panic!("ein abgelehnter Unlock ist kein Erfolg"),
        }
    }

    #[test]
    fn sperren_landet_als_eintrag_in_der_kette() {
        let mut t = Loopback::new(vec![antwort(&[0x71, 0x01, 0xC0, 0x09])]);
        let mut kette = AuditKette::neu();

        SfdClient::new()
            .sperren_protokolliert(&mut t, 100, "VIN", &mut kette, 9)
            .unwrap();

        let eintraege = kette.eintraege();
        assert_eq!(eintraege.len(), 1);
        assert_eq!(eintraege[0].dienst, DIENST_SPERREN);
        assert!(matches!(eintraege[0].ergebnis, Ergebnis::Erfolg));
    }
}
