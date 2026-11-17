//! VAG-SFD über UDS: was der Bildschirm wirklich tut.
//!
//! Alles hier stammt aus dem Ablauf, der am 17.11.2026 in `SfdAbschnitt.kt`
//! sichtbar ist: `22 01 74` lesen, in die Extended Session (`10 03`), dann die
//! drei `31 01 C0 04/08/09`-Schritte. Der Client macht daraus Nachrichten und
//! wertet sie aus; alles, was nicht belegt ist, bleibt roh und wird angezeigt.
//!
//! ## Was hier *nicht* steht
//!
//! Keine Seed/Key-Algorithmen, keine Kennungen für weitere Modelle, keine
//! Deutung der Statusbytes über das hinaus, was der Bildschirm ohnehin zeigt.
//! Die Statusbytes sind auf dem Gerät bisher **nicht** verifiziert — die
//! Deutung (Byte 0 `00`/`01`, Byte 1 Rolle, Byte 2–3 Minuten) steht in
//! `docs/geraetepruefung.md` als Annahme, nicht als Ergebnis.

use crate::isotp::IsotpChannel;
use crate::transport::{Transport, TransportError};
use crate::uds::UdsFehler;

/// Dienstbyte für ReadDataByIdentifier — derselbe Wert wie
/// [`crate::uds::DIENST_READ_DID`].
pub const DIENST_READ_DID: u8 = crate::uds::DIENST_READ_DID;
/// Antwort auf ReadDataByIdentifier.
pub const ANTWORT_READ_DID: u8 = crate::uds::ANTWORT_READ_DID;
/// Kennung für den SFD-Status (Baustein 74, Datensatz 01).
pub const DID_SFD_STATUS: u16 = 0xF174;
/// Alternativkennung, die auf manchen Steuergeräten den Status liefert.
pub const DID_SFD_STATUS_ALT: u16 = 0x0174;
/// Extended Session, wie sie SFD verlangt.
pub const SITZUNG_EXTENDED: u8 = 0x03;
/// Dienst „ReadDataByIdentifier“.
pub const DIENST_READ_DID_A: u8 = 0x22;
/// Routinenkennung-Basis: das niedrige Byte trägt den Schritt.
const ROUTINE_BASIS: u16 = 0xC000;
/// Schritt 1: Challenge lesen.
pub const SCHRITT_CHALLENGE: u8 = 0x04;
/// Schritt 2: Token senden.
pub const SCHRITT_TOKEN: u8 = 0x08;
/// Schritt 3: wieder sperren.
pub const SCHRITT_SPERREN: u8 = 0x09;
/// Die erste Zeile, die auf einer echten Antwort stand — als Beispiel, nicht
/// als Sollwert. Ein Test prüft nur, dass sie als Rohwert ankommt.
pub const BEISPIEL_STATUS: &[u8] = &[0x00, 0x01, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x00];

/// Fehler eines SFD-Aufrufs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SfdError {
    /// Transportfehler — nichts empfangen, Kabel, Gerät weg.
    Transport(TransportError),
    /// Das Steuergerät hat abgelehnt (`7F <Dienst> <NRC>`).
    Nrc { dienst: u8, code: u8 },
    /// Antwort gehört zu einem anderen Dienst.
    FalscherDienst { erwartet: u8, gesehen: u8 },
    /// Antwort gehört zu einem anderen Schritt.
    FalscherModus { erwartet: u8, gesehen: u8 },
    /// Antwort ist kürzer als ihr Kopf.
    ZuKurz { gelesen: usize, mindestens: usize },
    /// Die Nutzlast ist leer, wo eine erwartet wurde.
    LeereNutzlast,
    /// Token-Bestätigung passt nicht zum gesendeten Token.
    TokenPasstNicht,
}

impl std::fmt::Display for SfdError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SfdError::Transport(e) => write!(f, "{e}"),
            SfdError::Nrc { dienst, code } => write!(
                f,
                "abgelehnt (Dienst 0x{dienst:02X}, NRC 0x{code:02X}: {})",
                crate::uds::nrc_text(*code)
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
            SfdError::LeereNutzlast => write!(f, "Antwort ohne Nutzlast, wo eine erwartet war"),
            SfdError::TokenPasstNicht => write!(f, "Token-Bestätigung passt nicht zum Token"),
        }
    }
}

impl std::error::Error for SfdError {}

/// Fehler an der FFI-Grenze: Transport oder SFD.
///
/// Eine Ebene, weil die App beides gleich behandelt: sie zeigt den Klartext.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SfdFehler {
    /// Transportfehler.
    Transport(TransportError),
    /// SFD-Fehler.
    Sfd(SfdError),
}

impl std::fmt::Display for SfdFehler {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
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

/// Routinenkennung für einen Schritt: `0xC0<modus>`.
///
/// Der Schritt steht im niedrigen Byte — `31 01 C0 04` ist Routine `0xC004`,
/// nicht zwei unabhängige Bytes. Wer das verwechselt, baut Anfragen, die das
/// Steuergerät mit `0x31` ablehnt, und sucht den Fehler dann am Bus.
pub const fn routine_kennung(modus: u8) -> u16 {
    ROUTINE_BASIS | modus as u16
}

/// Kennung des ersten Schritts — der Wert, den `31 01 …` trägt.
pub const ROUTINE_KENNUNG: u8 = SCHRITT_CHALLENGE;
/// Dienstbyte für RoutineControl.
pub const DIENST_ROUTINE: u8 = crate::uds::DIENST_ROUTINE;
/// Subfunktion `startRoutine`.
pub const SUB_START: u8 = crate::uds::ROUTINE_START;
/// Antwort auf RoutineControl.
pub const ANTWORT_ROUTINE: u8 = crate::uds::ANTWORT_ROUTINE;

/// Status eines SFD-Bausteins, roh und gedeutet.
///
/// `roh` ist das, was auf der Leitung stand. Die gedeuteten Felder sind eine
/// **Annahme** (siehe Modulkopf) und stehen nur da, damit der Bildschirm etwas
/// Lesbares zeigen kann; geglaubt werden darf nur `roh`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SfdStatus {
    /// Die Bytes, wie sie kamen — auch die, die hier niemand deutet.
    pub roh: Vec<u8>,
    /// Byte 0: `00` gesperrt, `01` frei (Annahme).
    pub zustand: Option<u8>,
    /// Byte 1: Rolle des Bausteins (Annahme).
    pub rolle: Option<u8>,
    /// Byte 2–3: Minuten, Big Endian (Annahme).
    pub minuten: Option<u16>,
}

impl SfdStatus {
    /// Meldet das Steuergerät „frei“?
    ///
    /// Nur `01` zählt; alles andere — auch ein fehlendes Byte — ist „nicht
    /// frei“. Ein geratenes „frei“ wäre die gefährlichste Ausgabe dieses
    /// Bildschirms.
    pub fn ist_frei(&self) -> bool {
        self.zustand == Some(0x01)
    }
}

/// Wertet die Nutzlast von `62 F1 74` aus.
///
/// Fehlt eine der drei gedeuteten Stellen, bleibt das Feld `None` — nicht `0`.
pub fn parse_status(nutzlast: &[u8]) -> Result<SfdStatus, SfdError> {
    if nutzlast.is_empty() {
        return Err(SfdError::LeereNutzlast);
    }
    let zustand = Some(nutzlast[0]);
    let rolle = nutzlast.get(1).copied();
    let minuten = if nutzlast.len() >= 4 {
        Some(u16::from_be_bytes([nutzlast[2], nutzlast[3]]))
    } else {
        None
    };
    Ok(SfdStatus {
        roh: nutzlast.to_vec(),
        zustand,
        rolle,
        minuten,
    })
}

/// Antwort auf einen Challenge-Schritt.
pub struct Challenge {
    /// Die Challenge-Bytes, so wie sie kamen.
    pub bytes: Vec<u8>,
}

/// Wertet `71 01 C0 04 <Challenge>` aus.
///
/// Die Nutzlast ist hier **kein** Statusrecord: SFD legt seine Challenge
/// hinein. Deshalb prüft [`crate::uds::routine_kopf`] den Kopf, und die
/// Bedeutung der Bytes bleibt in diesem Modul.
fn parse_challenge(antwort: &[u8], modus: u8) -> Result<Challenge, SfdFehler> {
    let nutzlast = crate::uds::routine_kopf(antwort, SUB_START, routine_kennung(modus))?;
    if nutzlast.is_empty() {
        return Err(SfdError::LeereNutzlast.into());
    }
    Ok(Challenge {
        bytes: nutzlast.to_vec(),
    })
}

/// Prüft die Bestätigung eines Token-Schritts.
///
/// `gesendet` ist das Token, das raus ging; das Steuergerät bestätigt es in
/// der Nutzlast. Weicht es ab, ist der Schritt nicht gelungen — das als Erfolg
/// zu melden wäre der Fehler, den dieser Bildschirm nicht machen darf.
fn parse_token_bestaetigung(
    antwort: &[u8],
    modus: u8,
    gesendet: &[u8],
) -> Result<(), SfdFehler> {
    let nutzlast = crate::uds::routine_kopf(antwort, SUB_START, routine_kennung(modus))?;
    // Leere Nutzlast ist die knappste Form der Bestätigung und zulässig.
    if nutzlast.is_empty() {
        return Ok(());
    }
    if nutzlast != gesendet {
        return Err(SfdError::TokenPasstNicht.into());
    }
    Ok(())
}

/// Antwort auf einen Sperr-Schritt.
fn parse_sperren(antwort: &[u8], modus: u8) -> Result<(), SfdFehler> {
    let _ = crate::uds::routine_kopf(antwort, SUB_START, routine_kennung(modus))?;
    Ok(())
}

/// Wertet `62 F1 74 <Status>` aus.
pub fn parse_sfd_status(antwort: &[u8], did: u16) -> Result<SfdStatus, SfdFehler> {
    let nutzlast = crate::uds::parse_read_did(antwort, did)?;
    Ok(parse_status(&nutzlast)?)
}

/// Wertet `50 03` aus (Sitzungswechsel vor SFD).
pub fn parse_sitzung(antwort: &[u8]) -> Result<(), SfdFehler> {
    crate::uds::parse_sitzung(antwort, SITZUNG_EXTENDED)?;
    Ok(())
}

/// SFD-Ablauf über einen ISO-TP-Kanal.
///
/// Die Anfragen sind reine Bytefolgen; dieser Typ erledigt Senden, Warten und
/// Auswerten inklusive der Aufschübe (`NRC 0x78`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SfdClient {
    /// Der allgemeine Client — er besitzt den ISO-TP-Kanal und die einzige
    /// Sendestelle. `SfdClient` fügt nur die SFD-Nachrichten hinzu.
    uds: crate::uds::UdsClient,
}

impl Default for SfdClient {
    fn default() -> Self {
        Self::new()
    }
}

impl SfdClient {
    /// Standardadressierung wie [`crate::uds::UdsClient::new`].
    pub const fn new() -> Self {
        SfdClient {
            uds: crate::uds::UdsClient::new(),
        }
    }

    /// Für Steuergeräte mit abweichender Adressierung.
    pub const fn with_ids(req_id: u32, rsp_id: u32) -> Self {
        SfdClient {
            uds: crate::uds::UdsClient::with_ids(req_id, rsp_id),
        }
    }

    /// Der gemeinsame Client — für Aufrufer, die den Kanal selbst brauchen.
    pub fn uds(&self) -> &crate::uds::UdsClient {
        &self.uds
    }

    /// `22 F1 74` — Status lesen und roh mitliefern.
    pub fn status<T: Transport>(
        &self,
        t: &mut T,
        did: u16,
        timeout_ms: u32,
    ) -> Result<SfdStatus, SfdFehler> {
        let antwort = self.uds.runde(t, &crate::uds::read_did_anfrage(did), timeout_ms)?;
        parse_sfd_status(&antwort, did)
    }

    /// `10 03` — Extended Session.
    pub fn extended_session<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> Result<(), SfdFehler> {
        let antwort = self
            .uds
            .runde(t, &crate::uds::sitzung_anfrage(SITZUNG_EXTENDED), timeout_ms)?;
        parse_sitzung(&antwort)
    }

    /// `31 01 C0 04` — Challenge lesen.
    pub fn challenge<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> Result<Challenge, SfdFehler> {
        let antwort = self.uds.runde(
            t,
            &crate::uds::routine_start_anfrage(routine_kennung(SCHRITT_CHALLENGE), &[]),
            timeout_ms,
        )?;
        parse_challenge(&antwort, SCHRITT_CHALLENGE)
    }

    /// `31 01 C0 08 <Token>` — Token senden.
    pub fn token<T: Transport>(
        &self,
        t: &mut T,
        token: &[u8],
        timeout_ms: u32,
    ) -> Result<(), SfdFehler> {
        let antwort = self.uds.runde(
            t,
            &crate::uds::routine_start_anfrage(routine_kennung(SCHRITT_TOKEN), token),
            timeout_ms,
        )?;
        parse_token_bestaetigung(&antwort, SCHRITT_TOKEN, token)
    }

    /// `31 01 C0 09` — wieder sperren.
    pub fn sperren<T: Transport>(&self, t: &mut T, timeout_ms: u32) -> Result<(), SfdFehler> {
        let antwort = self.uds.runde(
            t,
            &crate::uds::routine_start_anfrage(routine_kennung(SCHRITT_SPERREN), &[]),
            timeout_ms,
        )?;
        parse_sperren(&antwort, SCHRITT_SPERREN)
    }

    /// Der ISO-TP-Kanal des gemeinsamen Clients.
    pub fn iso(&self) -> &IsotpChannel {
        self.uds.iso()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::isotp::RSP_ID;
    use crate::loopback::Loopback;
    use crate::transport::CanFrame;

    fn antwort(bytes: &[u8]) -> CanFrame {
        let mut f = Vec::with_capacity(bytes.len() + 1);
        f.push(bytes.len() as u8);
        f.extend_from_slice(bytes);
        CanFrame::new(RSP_ID, &f)
    }

    #[test]
    fn routine_kennung_legt_den_schritt_in_das_niedrige_byte() {
        assert_eq!(routine_kennung(SCHRITT_CHALLENGE), 0xC004);
        assert_eq!(routine_kennung(SCHRITT_TOKEN), 0xC008);
        assert_eq!(routine_kennung(SCHRITT_SPERREN), 0xC009);
    }
}
