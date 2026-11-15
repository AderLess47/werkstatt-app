//! SFD — „Schutz Fahrzeug Diagnose" (VAG) über UDS.
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
//!
//! `31` ist RoutineControl mit `startRoutine` (Subfunktion `01`), `C0` die
//! Routine-Kennung und das vierte Byte der Schritt: `04` Challenge lesen,
//! `08` Token senden, `09` wieder sperren. Die Rolle ist ein Byte — welchen
//! Wert ein Fahrzeug erwartet, steht in seiner Diagnosebeschreibung; hier wird
//! nur geprüft, dass sie nicht 0 ist.
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
//! wird als [`SfdError::Nrc`] zurückgegeben — samt Klartext, weil „abgelehnt"
//! allein in der Werkstatt nicht weiterhilft.

use std::fmt;

use crate::isotp::IsotpChannel;
use crate::transport::{Result as TransportResult, Transport, TransportError};

/// Dienst „ReadDataByIdentifier".
pub const DIENST_READ_DATA: u8 = 0x22;
/// Dienst „RoutineControl".
pub const DIENST_ROUTINE: u8 = 0x31;
/// Subfunktion `startRoutine`.
pub const SUB_START: u8 = 0x01;

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
pub const ANTWORT_ROUTINE: u8 = 0x71;
/// Positive Antwort auf ReadDataByIdentifier.
pub const ANTWORT_READ_DATA: u8 = 0x62;
/// Negative Antwort (`7F`).
pub const ANTWORT_NEGATIV: u8 = 0x7F;
/// NRC „responsePending" — kein Ergebnis, sondern ein Aufschub.
pub const NRC_RESPONSE_PENDING: u8 = 0x78;

/// Längster Token, der in eine ISO-TP-Nachricht passt.
///
/// ISO 15765-2 erlaubt 4095 Byte je Nachricht (12-Bit-Längenfeld). Davon gehen
/// vier Byte Kopf ab (`31 01 C0 08`) — mehr passt nicht in **eine** Nachricht,
/// und ein zweiter Block wäre ein anderes Protokoll.
pub const MAX_TOKEN: usize = 0x0FFF - 4;

/// Höchstzahl an Aufschüben (`NRC 0x78`), die ein Aufruf abwartet.
pub const MAX_AUFSCHUB: usize = 3;

/// Frist nach einem Aufschub (`P2*`), in Millisekunden.
pub const P2_STERN_MS: u32 = 5_000;

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

/// Klartext zu einem NRC (ISO 14229-1, Anhang A).
///
/// Unbekannte Codes bleiben als Zahl stehen — ein erfundener Text wäre
/// schlimmer als die Zahl, die im Fehlerspeicher des Testers landet.
pub fn nrc_text(code: u8) -> &'static str {
    match code {
        0x10 => "generalReject",
        0x11 => "serviceNotSupported",
        0x12 => "subFunctionNotSupported",
        0x13 => "incorrectMessageLengthOrInvalidFormat",
        0x14 => "responseTooLong",
        0x21 => "busyRepeatRequest",
        0x22 => "conditionsNotCorrect",
        0x24 => "requestSequenceError",
        0x25 => "noResponseFromSubnetComponent",
        0x26 => "failurePreventsExecutionOfRequestedAction",
        0x31 => "requestOutOfRange",
        0x33 => "securityAccessDenied",
        0x35 => "invalidKey",
        0x36 => "exceedNumberOfAttempts",
        0x37 => "requiredTimeDelayNotExpired",
        0x72 => "generalProgrammingFailure",
        0x78 => "responsePending (Antwort kommt noch)",
        0x7E => "subFunctionNotSupportedInActiveSession",
        0x7F => "serviceNotSupportedInActiveSession",
        _ => "unbekannter NRC",
    }
}

/// Erkennt eine negative Antwort und gibt `(Dienst, NRC)` zurück.
///
/// `None` heißt: die Antwort ist nicht negativ — ob sie passt, entscheidet
/// danach der jeweilige Parser.
pub fn pruefe_nrc(antwort: &[u8]) -> Option<(u8, u8)> {
    if antwort.len() >= 3 && antwort[0] == ANTWORT_NEGATIV {
        Some((antwort[1], antwort[2]))
    } else {
        None
    }
}

/// Ist das ein Aufschub (`7F <Dienst> 78`)?
pub fn ist_aufschub(antwort: &[u8]) -> bool {
    matches!(pruefe_nrc(antwort), Some((_, NRC_RESPONSE_PENDING)))
}

/// `22 01 74` — Status des SFD-Schutzes lesen.
pub fn status_anfrage() -> Vec<u8> {
    vec![DIENST_READ_DATA, STATUS_DID[0], STATUS_DID[1]]
}

/// `31 01 C0 04 <Rolle>` — Challenge anfordern.
///
/// Die Rolle ist der einzige Parameter; sie kommt aus der Diagnosebeschreibung
/// des Fahrzeugs und wird hier nur auf „nicht 0" geprüft.
pub fn challenge_anfrage(rolle: u8) -> std::result::Result<Vec<u8>, SfdError> {
    if rolle == 0 {
        return Err(SfdError::RolleUngueltig { rolle });
    }
    Ok(vec![
        DIENST_ROUTINE,
        SUB_START,
        ROUTINE_KENNUNG,
        MODUS_CHALLENGE,
        rolle,
    ])
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
    let mut out = Vec::with_capacity(4 + token.len());
    out.extend_from_slice(&[DIENST_ROUTINE, SUB_START, ROUTINE_KENNUNG, MODUS_ENTSPERREN]);
    out.extend_from_slice(token);
    Ok(out)
}

/// `31 01 C0 09` — wieder sperren.
pub fn sperren_anfrage() -> Vec<u8> {
    vec![DIENST_ROUTINE, SUB_START, ROUTINE_KENNUNG, MODUS_SPERREN]
}

/// Rohe Statusantwort (`62 01 74 …`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SfdStatus {
    /// Bytes hinter dem Echo — die Statusinformation des Steuergeräts.
    pub daten: Vec<u8>,
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
    })
}

/// Wertet `71 01 C0 04 <Challenge>` aus und gibt die Challenge zurück.
///
/// Eine Response ohne Challenge-Bytes (`71 01 C0 04`, 4 Byte) ist ungültig:
/// das Steuergerät hätte dann nichts geliefert, womit sich ein Token bilden
/// ließe.
pub fn parse_challenge(antwort: &[u8]) -> std::result::Result<Vec<u8>, SfdError> {
    parse_routine(antwort, MODUS_CHALLENGE)?;
    // `71 01 C0 04` sind vier Byte Kopf — die Challenge beginnt bei Byte 4,
    // nicht bei 5: Byte 4 ist die Subfunktion/Routine, nicht die Nutzlast.
    const KOPF: usize = 4;
    if antwort.len() <= KOPF {
        return Err(SfdError::ZuKurz {
            gelesen: antwort.len(),
            mindestens: KOPF + 1,
        });
    }
    Ok(antwort[KOPF..].to_vec())
}

/// Wertet die Antwort auf das Senden des Tokens aus (`71 01 C0 08`).
pub fn parse_entsperrt(antwort: &[u8]) -> std::result::Result<(), SfdError> {
    parse_routine(antwort, MODUS_ENTSPERREN)
}

/// Wertet die Antwort auf das Sperren aus (`71 01 C0 09`).
pub fn parse_gesperrt(antwort: &[u8]) -> std::result::Result<(), SfdError> {
    parse_routine(antwort, MODUS_SPERREN)
}

/// Gemeinsamer Kopf `71 01 C0 <Modus>`.
fn parse_routine(antwort: &[u8], modus: u8) -> std::result::Result<(), SfdError> {
    if let Some((dienst, code)) = pruefe_nrc(antwort) {
        return Err(SfdError::Nrc { dienst, code });
    }
    const KOPF: usize = 4;
    if antwort.len() < KOPF {
        return Err(SfdError::ZuKurz {
            gelesen: antwort.len(),
            mindestens: KOPF,
        });
    }
    if antwort[0] != ANTWORT_ROUTINE {
        return Err(SfdError::FalscherDienst {
            erwartet: ANTWORT_ROUTINE,
            gesehen: antwort[0],
        });
    }
    if antwort[1] != SUB_START || antwort[2] != ROUTINE_KENNUNG {
        return Err(SfdError::FalscherModus {
            erwartet: ROUTINE_KENNUNG,
            gesehen: antwort[2],
        });
    }
    if antwort[3] != modus {
        return Err(SfdError::FalscherModus {
            erwartet: modus,
            gesehen: antwort[3],
        });
    }
    Ok(())
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

/// SFD-Ablauf über einen ISO-TP-Kanal.
///
/// Die Anfragen sind reine Bytefolgen; dieser Typ erledigt Senden, Warten und
/// Auswerten inklusive der Aufschübe (`NRC 0x78`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SfdClient {
    iso: IsotpChannel,
}

impl Default for SfdClient {
    fn default() -> Self {
        Self::new()
    }
}

impl SfdClient {
    pub const fn new() -> Self {
        SfdClient {
            iso: IsotpChannel::new(),
        }
    }

    pub const fn with_ids(req_id: u32, rsp_id: u32) -> Self {
        SfdClient {
            iso: IsotpChannel::with_ids(req_id, rsp_id),
        }
    }

    /// Schickt `anfrage` und liefert die Antwort — Aufschübe werden abgewartet.
    fn runde<T: Transport>(
        &self,
        t: &mut T,
        anfrage: &[u8],
        timeout_ms: u32,
    ) -> std::result::Result<Vec<u8>, SfdFehler> {
        self.iso.send(t, anfrage)?;
        let mut frist = timeout_ms;
        let mut aufschuebe = 0;
        loop {
            let antwort = self.iso.recv(t, frist)?;
            if !ist_aufschub(&antwort) {
                return Ok(antwort);
            }
            if aufschuebe >= MAX_AUFSCHUB {
                // Aufschub ohne Ende: als Ablehnung melden, nicht als Timeout —
                // die Ursache ist das Steuergerät, nicht die Leitung.
                let (dienst, code) = pruefe_nrc(&antwort).unwrap_or((DIENST_ROUTINE, NRC_RESPONSE_PENDING));
                return Err(SfdError::Nrc { dienst, code }.into());
            }
            aufschuebe += 1;
            frist = P2_STERN_MS;
        }
    }

    /// `22 01 74` — Status lesen.
    pub fn status<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> std::result::Result<SfdStatus, SfdFehler> {
        let antwort = self.runde(t, &status_anfrage(), timeout_ms)?;
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
        let antwort = self.runde(t, &anfrage, timeout_ms)?;
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
        let antwort = self.runde(t, &anfrage, timeout_ms)?;
        Ok(parse_entsperrt(&antwort)?)
    }

    /// Wieder sperren.
    pub fn sperren<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> std::result::Result<(), SfdFehler> {
        let antwort = self.runde(t, &sperren_anfrage(), timeout_ms)?;
        Ok(parse_gesperrt(&antwort)?)
    }

    pub fn iso(&self) -> &IsotpChannel {
        &self.iso
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
pub fn aus_transport(ergebnis: TransportResult<Vec<u8>>) -> std::result::Result<Vec<u8>, SfdFehler> {
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
            SfdStatus { daten: vec![] }
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
        SfdClient::new().entsperren(&mut t, &[0x01, 0x02], 100).unwrap();
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
}
