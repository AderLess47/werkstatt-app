//! UDS — ISO 14229-1: die Dienste, die eine Diagnose braucht.
//!
//! Dieses Modul ist die **untere** Schicht: es kennt ISO-TP, baut Anfragen,
//! wartet Aufschübe (`NRC 0x78`) ab und wertet die positive Antwort aus. Was
//! ein bestimmter Dienst *bedeutet*, gehört nicht hierher — `sfd` ist das
//! Beispiel dafür: es setzt auf diesen Client auf und weiß, welche Rolle wann
//! gesendet wird.
//!
//! ## Verhältnis zu [`crate::sfd`]
//!
//! `sfd` ist der erste Aufrufer: es baut nur noch seine eigenen Nachrichten
//! (`22 01 74`, `31 01 C0 …`) und schickt sie durch [`UdsClient::runde`].
//! NRC-Texte, Aufschub-Grenze und Sitzungsauswertung liegen hier, damit es
//! **eine** Sendestelle und **eine** NRC-Tabelle gibt. Neue Dienste entstehen
//! in diesem Modul, nie dort.
//!
//! ## Aufschübe
//!
//! `7F <Dienst> 78` heißt nicht „abgelehnt", sondern „rechne noch". Ein
//! Steuergerät darf das mehrfach senden; ohne Grenze würde der Aufrufer bei
//! einem hängenden Steuergerät unendlich warten. Deshalb höchstens
//! [`MAX_AUFSCHUB`] Aufschübe, danach gilt es als Ablehnung — die Ursache liegt
//! dann im Steuergerät, nicht in der Leitung, und ein `Timeout` würde in die
//! falsche Richtung zeigen.

use std::fmt;

use crate::isotp::IsotpChannel;
use crate::transport::{Result as TransportResult, Transport, TransportError};

/// Dienst „DiagnosticSessionControl".
pub const DIENST_SITZUNG: u8 = 0x10;
/// Positive Antwort auf DiagnosticSessionControl.
pub const ANTWORT_SITZUNG: u8 = 0x50;
/// Dienst „ReadDataByIdentifier".
pub const DIENST_READ_DID: u8 = 0x22;
/// Positive Antwort auf ReadDataByIdentifier.
pub const ANTWORT_READ_DID: u8 = 0x62;
/// Negative Antwort (`7F <Dienst> <NRC>`).
pub const ANTWORT_NEGATIV: u8 = 0x7F;
/// NRC „responsePending" — kein Ergebnis, sondern ein Aufschub.
pub const NRC_RESPONSE_PENDING: u8 = 0x78;
/// Subfunktion `extendedDiagnosticSession`.
pub const SITZUNG_EXTENDED: u8 = 0x03;
/// Dienst „ReadDTCInformation".
pub const DIENST_READ_DTC: u8 = 0x19;
/// Positive Antwort auf ReadDTCInformation.
pub const ANTWORT_READ_DTC: u8 = 0x59;
/// Unterfunktion `reportDTCByStatusMask`.
pub const READ_DTC_BY_STATUS_MASK: u8 = 0x02;
/// Statusmaske „alle unterstützten Fehler".
pub const DTC_MASKE_ALLE: u8 = 0xFF;
/// Bit 7 einer Unterfunktion: positive Antwort unterdrücken.
pub const UNTERDRUECKUNGS_BIT: u8 = 0x80;

/// Höchstzahl an Aufschüben (`NRC 0x78`), die ein Aufruf abwartet.
pub const MAX_AUFSCHUB: usize = 3;

/// Frist nach einem Aufschub (`P2*`), in Millisekunden.
///
/// `P2*` ist der Wert, den das Steuergerät in `50 xx` mitschickt; hier steht
/// der konservative Vorgabewert aus ISO 14229-1, weil die Antwort auf den
/// Aufschub selbst kein `P2*` enthält.
pub const P2_STERN_MS: u32 = 5_000;

/// Fehler eines UDS-Aufrufs: Transport oder Protokoll.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UdsFehler {
    /// Transportfehler — nichts empfangen, Kabel, Gerät weg.
    ///
    /// Eigener Fall statt „Protokollfehler", weil die Reaktion eine andere
    /// ist: ein Timeout heißt „Zündung? Adressierung?", ein NRC heißt „das
    /// Steuergerät will nicht".
    Transport(TransportError),
    /// Das Steuergerät hat abgelehnt (`7F <Dienst> <NRC>`).
    Nrc { dienst: u8, code: u8 },
    /// Antwort gehört zu einem anderen Dienst.
    FalscherDienst { erwartet: u8, gesehen: u8 },
    /// Antwort gehört zu einer anderen Subfunktion (Sitzung).
    FalscheSubfunktion { erwartet: u8, gesehen: u8 },
    /// Antwort gehört zu einer anderen DID.
    ///
    /// Wichtig für die Werkstatt: ein Steuergerät, das `62 F1 8A` auf die
    /// Anfrage nach `F190` antwortet, ist nicht dasselbe wie eines ohne
    /// Antwort — die Kennung stimmt nicht, und die Nutzlast gehört zu etwas
    /// anderem.
    FalscheDid { erwartet: [u8; 2], gesehen: [u8; 2] },
    /// Antwort ist kürzer als ihr eigener Kopf.
    ZuKurz { gelesen: usize, mindestens: usize },
    /// Die DTC-Liste endet mitten im Eintrag.
    ///
    /// Ein Eintrag ist vier Byte lang; bleibt ein Rest, hat das Steuergerät
    /// aufgehört zu senden. Den halben Fehler zu raten wäre schlimmer, als ihn
    /// zu melden — ein falscher DTC führt in der Werkstatt zur falschen Reparatur.
    DtcRest { uebrig: usize },
}

impl fmt::Display for UdsFehler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            UdsFehler::Transport(e) => write!(f, "{e}"),
            UdsFehler::Nrc { dienst, code } => write!(
                f,
                "abgelehnt (Dienst 0x{dienst:02X}, NRC 0x{code:02X}: {})",
                nrc_text(*code)
            ),
            UdsFehler::FalscherDienst { erwartet, gesehen } => write!(
                f,
                "unerwarteter Dienst: erwartet 0x{erwartet:02X}, gesehen 0x{gesehen:02X}"
            ),
            UdsFehler::FalscheSubfunktion { erwartet, gesehen } => write!(
                f,
                "unerwartete Subfunktion: erwartet 0x{erwartet:02X}, gesehen 0x{gesehen:02X}"
            ),
            UdsFehler::FalscheDid { erwartet, gesehen } => write!(
                f,
                "unerwartete Kennung: erwartet {:02X}{:02X}, gesehen {:02X}{:02X}",
                erwartet[0], erwartet[1], gesehen[0], gesehen[1]
            ),
            UdsFehler::ZuKurz {
                gelesen,
                mindestens,
            } => write!(
                f,
                "Antwort zu kurz: {gelesen} Byte, mindestens {mindestens} erwartet"
            ),
            UdsFehler::DtcRest { uebrig } => write!(
                f,
                "DTC-Liste endet mitten im Eintrag: {uebrig} Byte übrig (je Eintrag 4)"
            ),
        }
    }
}

impl std::error::Error for UdsFehler {}

impl From<TransportError> for UdsFehler {
    fn from(e: TransportError) -> Self {
        UdsFehler::Transport(e)
    }
}

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
/// `None` heißt: nicht negativ. Ob die Antwort zum Aufruf passt, entscheidet
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

/// `10 <Sitzung>` — Sitzungswechsel.
pub fn sitzung_anfrage(sitzung: u8) -> Vec<u8> {
    vec![DIENST_SITZUNG, sitzung]
}

/// `22 <DID>` — Kennung lesen.
pub fn read_did_anfrage(did: u16) -> Vec<u8> {
    vec![DIENST_READ_DID, (did >> 8) as u8, did as u8]
}

/// Antwort auf einen Sitzungswechsel.
///
/// `P2` und `P2*` stehen nur da, wenn das Steuergerät sie mitschickt — das ist
/// erlaubt, aber nicht Pflicht. Sie sind die einzige Stelle, an der das
/// Steuergerät seine eigenen Fristen nennt; wer sie ignoriert, wartet entweder
/// zu kurz oder unnötig lange.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionAntwort {
    /// Die Sitzung, die das Steuergerät bestätigt hat.
    pub sitzung: u8,
    /// `P2` in Millisekunden (Antwortfrist im Normalbetrieb).
    pub p2_ms: Option<u16>,
    /// `P2*` in Millisekunden (Frist nach einem Aufschub).
    pub p2_stern_ms: Option<u16>,
}

/// Wertet `50 <Sitzung> [P2 [P2*]]` aus.
pub fn parse_sitzung(
    antwort: &[u8],
    sitzung: u8,
) -> std::result::Result<SessionAntwort, UdsFehler> {
    if let Some((dienst, code)) = pruefe_nrc(antwort) {
        return Err(UdsFehler::Nrc { dienst, code });
    }
    if antwort.len() < 2 {
        return Err(UdsFehler::ZuKurz {
            gelesen: antwort.len(),
            mindestens: 2,
        });
    }
    if antwort[0] != ANTWORT_SITZUNG {
        return Err(UdsFehler::FalscherDienst {
            erwartet: ANTWORT_SITZUNG,
            gesehen: antwort[0],
        });
    }
    if antwort[1] != sitzung {
        return Err(UdsFehler::FalscheSubfunktion {
            erwartet: sitzung,
            gesehen: antwort[1],
        });
    }
    // Beide Werte sind Big Endian und je zwei Byte lang. Ein halber Wert wird
    // nicht geraten: fehlt das zweite Byte, gilt der Wert als nicht genannt.
    let p2_ms = if antwort.len() >= 4 {
        Some(u16::from_be_bytes([antwort[2], antwort[3]]))
    } else {
        None
    };
    let p2_stern_ms = if antwort.len() >= 6 {
        Some(u16::from_be_bytes([antwort[4], antwort[5]]))
    } else {
        None
    };
    Ok(SessionAntwort {
        sitzung,
        p2_ms,
        p2_stern_ms,
    })
}

/// Wertet `62 <DID> <Daten>` aus und gibt die Daten zurück.
///
/// Eine leere Nutzlast (`62 F1 90`, drei Byte) ist gültig: manche Kennungen
/// antworten ohne Inhalt, und das ist etwas anderes als eine fehlende Antwort.
pub fn parse_read_did(antwort: &[u8], did: u16) -> std::result::Result<Vec<u8>, UdsFehler> {
    if let Some((dienst, code)) = pruefe_nrc(antwort) {
        return Err(UdsFehler::Nrc { dienst, code });
    }
    const KOPF: usize = 3;
    if antwort.len() < KOPF {
        return Err(UdsFehler::ZuKurz {
            gelesen: antwort.len(),
            mindestens: KOPF,
        });
    }
    if antwort[0] != ANTWORT_READ_DID {
        return Err(UdsFehler::FalscherDienst {
            erwartet: ANTWORT_READ_DID,
            gesehen: antwort[0],
        });
    }
    let erwartet = [(did >> 8) as u8, did as u8];
    if antwort[1..3] != erwartet {
        return Err(UdsFehler::FalscheDid {
            erwartet,
            gesehen: [antwort[1], antwort[2]],
        });
    }
    Ok(antwort[KOPF..].to_vec())
}

/// Ein Fehlereintrag, so wie das Steuergerät ihn schickt.
///
/// `dtc` ist die Dreiergruppe aus ISO 14229-1 (zwei Byte Fehlercode, ein Byte
/// Fehlerart), `status` das Statusbyte. Beides bleibt roh: welche Bits ein
/// Hersteller belegt, steht in keiner Norm, die hier belegbar wäre — die
/// Auswertung nach `P0301` und Klartext gehört nach `j1979` (P2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DtcEintrag {
    /// Der Fehlercode, drei Byte, unverändert.
    pub dtc: [u8; 3],
    /// Das Statusbyte (`DTCStatus`), unverändert.
    pub status: u8,
}

/// Antwort auf `19 02`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DtcListe {
    /// Welche Statusbits dieses Steuergerät überhaupt führt.
    ///
    /// Nicht jede Steuergerätegeneration belegt alle acht Bits; wer die Maske
    /// ignoriert, hält ein nicht belegtes Bit für „nicht gesetzt".
    pub status_verfuegbarkeit: u8,
    /// Die Einträge in der Reihenfolge, in der sie kamen.
    pub eintraege: Vec<DtcEintrag>,
}

/// `19 02 <Maske>` — Fehlerspeicher nach Statusmaske lesen.
pub fn read_dtc_anfrage(maske: u8) -> Vec<u8> {
    vec![DIENST_READ_DTC, READ_DTC_BY_STATUS_MASK, maske]
}

/// Wertet `59 02 <Verfügbarkeit> [<DTC:3> <Status:1>]…` aus.
pub fn parse_dtc_liste(antwort: &[u8]) -> std::result::Result<DtcListe, UdsFehler> {
    const KOPF: usize = 3;
    const EINTRAG: usize = 4;
    if let Some((dienst, code)) = pruefe_nrc(antwort) {
        return Err(UdsFehler::Nrc { dienst, code });
    }
    if antwort.len() < KOPF {
        return Err(UdsFehler::ZuKurz {
            gelesen: antwort.len(),
            mindestens: KOPF,
        });
    }
    if antwort[0] != ANTWORT_READ_DTC {
        return Err(UdsFehler::FalscherDienst {
            erwartet: ANTWORT_READ_DTC,
            gesehen: antwort[0],
        });
    }
    // Bit 7 ist das Unterdrückungsbit der Anfrage und wird nicht
    // zurückgespiegelt. Ein Steuergerät, das es doch tut, ist kein Grund, die
    // Antwort zu verwerfen — deshalb auf beiden Seiten maskiert.
    let gesehen = antwort[1] & !UNTERDRUECKUNGS_BIT;
    if gesehen != READ_DTC_BY_STATUS_MASK {
        return Err(UdsFehler::FalscheSubfunktion {
            erwartet: READ_DTC_BY_STATUS_MASK,
            gesehen,
        });
    }
    let rest = &antwort[KOPF..];
    if rest.len() % EINTRAG != 0 {
        return Err(UdsFehler::DtcRest {
            uebrig: rest.len() % EINTRAG,
        });
    }
    let eintraege = rest
        .chunks_exact(EINTRAG)
        .map(|c| DtcEintrag {
            dtc: [c[0], c[1], c[2]],
            status: c[3],
        })
        .collect();
    Ok(DtcListe {
        status_verfuegbarkeit: antwort[2],
        eintraege,
    })
}

/// UDS-Client über einen ISO-TP-Kanal.
///
/// Zustandslos wie [`IsotpChannel`]: Sitzung und Sicherheitszugriff sind
/// Zustand des **Steuergeräts**, nicht des Testers. Wer ihn hier spiegeln
/// würde, müsste ihn nach jedem Timeout zurücksetzen — und läge nach dem
/// ersten echten Abbruch falsch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdsClient {
    iso: IsotpChannel,
}

impl Default for UdsClient {
    fn default() -> Self {
        Self::new()
    }
}

impl UdsClient {
    /// Standardadressierung: Anfragen `0x7E0`, Antworten `0x7E8`.
    pub const fn new() -> Self {
        UdsClient {
            iso: IsotpChannel::new(),
        }
    }

    /// Für Fahrzeuge mit abweichender Adressierung.
    pub const fn with_ids(req_id: u32, rsp_id: u32) -> Self {
        UdsClient {
            iso: IsotpChannel::with_ids(req_id, rsp_id),
        }
    }

    /// Schickt `anfrage` und liefert die Antwort — Aufschübe werden abgewartet.
    ///
    /// Das ist die **einzige** Stelle, die sendet und Aufschübe behandelt. Jeder
    /// Dienst weiter oben ruft sie auf; zwei solche Stellen würden sich früher
    /// oder später unterschiedlich verhalten, und kein Test würde es merken.
    pub fn runde<T: Transport>(
        &self,
        t: &mut T,
        anfrage: &[u8],
        timeout_ms: u32,
    ) -> std::result::Result<Vec<u8>, UdsFehler> {
        self.iso.send(t, anfrage)?;
        let mut frist = timeout_ms;
        let mut aufschuebe = 0;
        loop {
            let antwort = self.iso.recv(t, frist)?;
            if !ist_aufschub(&antwort) {
                return Ok(antwort);
            }
            if aufschuebe >= MAX_AUFSCHUB {
                // Aufschub ohne Ende: als Ablehnung melden, nicht als Timeout.
                let (dienst, code) =
                    pruefe_nrc(&antwort).unwrap_or((DIENST_SITZUNG, NRC_RESPONSE_PENDING));
                return Err(UdsFehler::Nrc { dienst, code });
            }
            aufschuebe += 1;
            frist = P2_STERN_MS;
        }
    }

    /// `10 <Sitzung>` — Sitzungswechsel, mit den Fristen aus der Antwort.
    pub fn session<T: Transport>(
        &self,
        t: &mut T,
        sitzung: u8,
        timeout_ms: u32,
    ) -> std::result::Result<SessionAntwort, UdsFehler> {
        let antwort = self.runde(t, &sitzung_anfrage(sitzung), timeout_ms)?;
        parse_sitzung(&antwort, sitzung)
    }

    /// `10 03` — Extended Session.
    pub fn extended<T: Transport>(
        &self,
        t: &mut T,
        timeout_ms: u32,
    ) -> std::result::Result<SessionAntwort, UdsFehler> {
        self.session(t, SITZUNG_EXTENDED, timeout_ms)
    }

    /// `22 <DID>` — Kennung lesen und die Nutzlast zurückgeben.
    pub fn read_did<T: Transport>(
        &self,
        t: &mut T,
        did: u16,
        timeout_ms: u32,
    ) -> std::result::Result<Vec<u8>, UdsFehler> {
        let antwort = self.runde(t, &read_did_anfrage(did), timeout_ms)?;
        parse_read_did(&antwort, did)
    }

    /// `19 02 <Maske>` — Fehlerspeicher lesen, Einträge roh.
    ///
    /// Kein Fehler gespeichert ist ein leeres Ergebnis, kein Fehlerfall: die
    /// Werkstatt will wissen „nichts drin" und nicht „Abruf fehlgeschlagen".
    pub fn read_dtc<T: Transport>(
        &self,
        t: &mut T,
        maske: u8,
        timeout_ms: u32,
    ) -> std::result::Result<DtcListe, UdsFehler> {
        let antwort = self.runde(t, &read_dtc_anfrage(maske), timeout_ms)?;
        parse_dtc_liste(&antwort)
    }

    pub fn iso(&self) -> &IsotpChannel {
        &self.iso
    }
}

/// Wandelt ein Transport-Ergebnis in einen UDS-Fehler um.
///
/// Für Aufrufer, die selbst senden und nur auswerten wollen.
pub fn aus_transport(
    ergebnis: TransportResult<Vec<u8>>,
) -> std::result::Result<Vec<u8>, UdsFehler> {
    Ok(ergebnis?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::isotp::RSP_ID;
    use crate::loopback::Loopback;
    use crate::transport::CanFrame;

    /// Baut einen Single Frame um eine UDS-Antwort (höchstens sieben Byte).
    fn antwort(bytes: &[u8]) -> CanFrame {
        let mut f = Vec::with_capacity(bytes.len() + 1);
        f.push(bytes.len() as u8);
        f.extend_from_slice(bytes);
        CanFrame::new(RSP_ID, &f)
    }

    fn aufschub(dienst: u8) -> CanFrame {
        antwort(&[0x7F, dienst, 0x78])
    }

    // --- Anfragen ---------------------------------------------------------

    #[test]
    fn sitzung_anfrage_ist_10_mit_subfunktion() {
        assert_eq!(sitzung_anfrage(0x03), vec![0x10, 0x03]);
        assert_eq!(sitzung_anfrage(0x02), vec![0x10, 0x02]);
    }

    #[test]
    fn read_did_anfrage_traegt_die_kennung_big_endian() {
        assert_eq!(read_did_anfrage(0xF190), vec![0x22, 0xF1, 0x90]);
        assert_eq!(read_did_anfrage(0x0001), vec![0x22, 0x00, 0x01]);
    }

    // --- Sitzung ----------------------------------------------------------

    #[test]
    fn sitzung_ohne_zeiten_wird_angenommen() {
        let a = parse_sitzung(&[0x50, 0x03], 0x03).unwrap();
        assert_eq!(a.sitzung, 0x03);
        assert_eq!(a.p2_ms, None);
        assert_eq!(a.p2_stern_ms, None);
    }

    #[test]
    fn sitzung_liest_p2_und_p2_stern() {
        // 0x0032 = 50 ms, 0x01F4 = 500 ms — so antworten die meisten Steuergeräte.
        let a = parse_sitzung(&[0x50, 0x03, 0x00, 0x32, 0x01, 0xF4], 0x03).unwrap();
        assert_eq!(a.p2_ms, Some(50));
        assert_eq!(a.p2_stern_ms, Some(500));
    }

    #[test]
    fn halber_p2_wird_nicht_geraten() {
        // Nur ein Byte von P2: der Wert gilt als nicht genannt, nicht als 0x00xx.
        let a = parse_sitzung(&[0x50, 0x03, 0x00], 0x03).unwrap();
        assert_eq!(a.p2_ms, None);
        assert_eq!(a.p2_stern_ms, None);
    }

    #[test]
    fn sitzung_nrc_wird_benannt() {
        let err = parse_sitzung(&[0x7F, 0x10, 0x12], 0x03).unwrap_err();
        assert_eq!(
            err,
            UdsFehler::Nrc {
                dienst: 0x10,
                code: 0x12
            }
        );
        assert!(err.to_string().contains("subFunctionNotSupported"), "{err}");
    }

    #[test]
    fn sitzung_mit_falscher_subfunktion_wird_abgelehnt() {
        assert_eq!(
            parse_sitzung(&[0x50, 0x02], 0x03).unwrap_err(),
            UdsFehler::FalscheSubfunktion {
                erwartet: 0x03,
                gesehen: 0x02
            }
        );
    }

    #[test]
    fn sitzung_zu_kurz_wird_abgelehnt() {
        assert_eq!(
            parse_sitzung(&[0x50], 0x03).unwrap_err(),
            UdsFehler::ZuKurz {
                gelesen: 1,
                mindestens: 2
            }
        );
    }

    #[test]
    fn sitzung_mit_falschem_dienst_wird_abgelehnt() {
        assert_eq!(
            parse_sitzung(&[0x51, 0x03], 0x03).unwrap_err(),
            UdsFehler::FalscherDienst {
                erwartet: 0x50,
                gesehen: 0x51
            }
        );
    }

    // --- Kennung lesen ----------------------------------------------------

    #[test]
    fn did_antwort_liefert_die_nutzlast() {
        let daten = parse_read_did(&[0x62, 0xF1, 0x90, 0x01, 0x02, 0x03], 0xF190).unwrap();
        assert_eq!(daten, vec![0x01, 0x02, 0x03]);
    }

    #[test]
    fn did_ohne_nutzlast_ist_gueltig() {
        assert_eq!(
            parse_read_did(&[0x62, 0xF1, 0x90], 0xF190).unwrap(),
            Vec::<u8>::new()
        );
    }

    #[test]
    fn did_mit_anderer_kennung_wird_abgelehnt() {
        // Das Steuergerät antwortet, aber zu einer anderen Kennung — die
        // Nutzlast gehört nicht zur Frage.
        assert_eq!(
            parse_read_did(&[0x62, 0xF1, 0x8A, 0xAA], 0xF190).unwrap_err(),
            UdsFehler::FalscheDid {
                erwartet: [0xF1, 0x90],
                gesehen: [0xF1, 0x8A]
            }
        );
    }

    #[test]
    fn did_mit_nrc_wird_abgelehnt() {
        assert_eq!(
            parse_read_did(&[0x7F, 0x22, 0x31], 0xF190).unwrap_err(),
            UdsFehler::Nrc {
                dienst: 0x22,
                code: 0x31
            }
        );
    }

    #[test]
    fn did_zu_kurz_wird_abgelehnt() {
        assert_eq!(
            parse_read_did(&[0x62, 0xF1], 0xF190).unwrap_err(),
            UdsFehler::ZuKurz {
                gelesen: 2,
                mindestens: 3
            }
        );
    }

    #[test]
    fn falscher_dienst_wird_abgelehnt() {
        assert_eq!(
            parse_read_did(&[0x63, 0xF1, 0x90], 0xF190).unwrap_err(),
            UdsFehler::FalscherDienst {
                erwartet: 0x62,
                gesehen: 0x63
            }
        );
    }

    // --- Ablauf gegen den Simulator ---------------------------------------

    #[test]
    fn session_am_simulator() {
        let mut t = Loopback::new(vec![antwort(&[0x50, 0x03, 0x00, 0x32, 0x01, 0xF4])]);
        let a = UdsClient::new().session(&mut t, 0x03, 100).unwrap();

        assert_eq!(a.sitzung, 0x03);
        assert_eq!(a.p2_ms, Some(50));
        // Auf der Leitung: len=2, 10, 03 — als Single Frame.
        assert_eq!(&t.sent()[0].data[..3], &[0x02, 0x10, 0x03]);
    }

    #[test]
    fn extended_schickt_10_03() {
        let mut t = Loopback::new(vec![antwort(&[0x50, 0x03])]);
        UdsClient::new().extended(&mut t, 100).unwrap();
        assert_eq!(&t.sent()[0].data[..3], &[0x02, 0x10, 0x03]);
    }

    #[test]
    fn read_did_am_simulator() {
        let mut t = Loopback::new(vec![antwort(&[0x62, 0xF1, 0x90, 0x42])]);
        let daten = UdsClient::new().read_did(&mut t, 0xF190, 100).unwrap();

        assert_eq!(daten, vec![0x42]);
        assert_eq!(&t.sent()[0].data[..4], &[0x03, 0x22, 0xF1, 0x90]);
    }

    #[test]
    fn lange_did_antwort_kommt_als_multi_frame() {
        // 20 Byte Antwort (Kopf + 17 Zeichen VIN) passen nicht in einen Rahmen:
        // First Frame, Flow Control, zwei Consecutive Frames.
        let vin = b"WVWZZZ1JZXW000001";
        let mut nutzlast = vec![0x62, 0xF1, 0x90];
        nutzlast.extend_from_slice(vin);

        let mut ff = vec![0x10u8, nutzlast.len() as u8];
        ff.extend_from_slice(&nutzlast[..6]);
        let mut cf1 = vec![0x21u8];
        cf1.extend_from_slice(&nutzlast[6..13]);
        let mut cf2 = vec![0x22u8];
        cf2.extend_from_slice(&nutzlast[13..]);

        let mut t = Loopback::new(vec![
            CanFrame::new(RSP_ID, &ff),
            CanFrame::new(RSP_ID, &cf1),
            CanFrame::new(RSP_ID, &cf2),
        ]);
        let daten = UdsClient::new().read_did(&mut t, 0xF190, 100).unwrap();

        assert_eq!(daten, vin);
        // Der Flow-Control-Rahmen ging auf die Anfrage-ID, nicht auf die Antwort-ID.
        let fc = t.sent().last().unwrap();
        assert_eq!(fc.data[0], 0x30);
        assert_eq!(fc.id, crate::isotp::REQ_ID);
    }

    #[test]
    fn aufschub_wird_abgewartet() {
        let mut t = Loopback::new(vec![aufschub(0x22), antwort(&[0x62, 0xF1, 0x90, 0x01])]);
        let daten = UdsClient::new().read_did(&mut t, 0xF190, 100).unwrap();

        assert_eq!(daten, vec![0x01]);
        // Genau eine Anfrage: nach dem Aufschub wird nicht neu gesendet.
        assert_eq!(t.sent().len(), 1);
    }

    #[test]
    fn dauerhafter_aufschub_bricht_ab() {
        let mut t = Loopback::new(vec![
            aufschub(0x22),
            aufschub(0x22),
            aufschub(0x22),
            aufschub(0x22),
        ]);
        let err = UdsClient::new().read_did(&mut t, 0xF190, 100).unwrap_err();

        assert_eq!(
            err,
            UdsFehler::Nrc {
                dienst: 0x22,
                code: 0x78
            }
        );
        assert!(err.to_string().contains("responsePending"), "{err}");
    }

    #[test]
    fn zeitueberschreitung_bleibt_zeitueberschreitung() {
        let mut t = Loopback::empty();
        let err = UdsClient::new().read_did(&mut t, 0xF190, 10).unwrap_err();
        assert_eq!(err, UdsFehler::Transport(TransportError::Timeout));
    }

    #[test]
    fn abweichende_kennungen_gehen_auf_die_gewuenschten_ids() {
        let mut t = Loopback::new(vec![antwort(&[0x50, 0x03])]);
        // Gateway-Adressierung statt Motor.
        let client = UdsClient::with_ids(0x710, 0x718);
        client.session(&mut t, 0x03, 100).unwrap();

        assert_eq!(client.iso().req_id(), 0x710);
        assert_eq!(t.sent()[0].id, 0x710);
    }

    // --- Fehlerspeicher ---------------------------------------------------

    #[test]
    fn read_dtc_anfrage_ist_19_02_mit_maske() {
        assert_eq!(read_dtc_anfrage(DTC_MASKE_ALLE), vec![0x19, 0x02, 0xFF]);
        assert_eq!(read_dtc_anfrage(0x08), vec![0x19, 0x02, 0x08]);
    }

    #[test]
    fn leere_dtc_liste_ist_gueltig() {
        // „Nichts gespeichert" ist ein Befund, kein Fehlerfall.
        let liste = parse_dtc_liste(&[0x59, 0x02, 0xFF]).unwrap();
        assert_eq!(liste.status_verfuegbarkeit, 0xFF);
        assert!(liste.eintraege.is_empty());
    }

    #[test]
    fn dtc_liste_wird_roh_gelesen() {
        let liste = parse_dtc_liste(&[
            0x59, 0x02, 0xFF, 0x03, 0x01, 0x00, 0x2F, 0xC1, 0x23, 0x45, 0x08,
        ])
        .unwrap();

        assert_eq!(liste.eintraege.len(), 2);
        assert_eq!(
            liste.eintraege[0],
            DtcEintrag {
                dtc: [0x03, 0x01, 0x00],
                status: 0x2F
            }
        );
        assert_eq!(
            liste.eintraege[1],
            DtcEintrag {
                dtc: [0xC1, 0x23, 0x45],
                status: 0x08
            }
        );
        // Kein Bit wird wegmaskiert: der Rohwert bleibt nachprüfbar, auch wo
        // die Bedeutung eines Bits unbekannt ist.
        assert_eq!(liste.status_verfuegbarkeit, 0xFF);
    }

    #[test]
    fn unterdrueckungsbit_in_der_antwort_stoert_nicht() {
        // 0x82 = 0x02 mit gesetztem Bit 7.
        let liste = parse_dtc_liste(&[0x59, 0x82, 0xFF, 0x03, 0x01, 0x00, 0x2F]).unwrap();
        assert_eq!(liste.eintraege.len(), 1);
    }

    #[test]
    fn abgeschnittener_dtc_eintrag_wird_abgelehnt() {
        assert_eq!(
            parse_dtc_liste(&[0x59, 0x02, 0xFF, 0x03, 0x01, 0x00]).unwrap_err(),
            UdsFehler::DtcRest { uebrig: 3 }
        );
    }

    #[test]
    fn dtc_liste_zu_kurz_wird_abgelehnt() {
        assert_eq!(
            parse_dtc_liste(&[0x59, 0x02]).unwrap_err(),
            UdsFehler::ZuKurz {
                gelesen: 2,
                mindestens: 3
            }
        );
    }

    #[test]
    fn dtc_liste_mit_falschem_dienst_wird_abgelehnt() {
        assert_eq!(
            parse_dtc_liste(&[0x5A, 0x02, 0xFF]).unwrap_err(),
            UdsFehler::FalscherDienst {
                erwartet: 0x59,
                gesehen: 0x5A
            }
        );
    }

    #[test]
    fn dtc_liste_mit_falscher_subfunktion_wird_abgelehnt() {
        // 19 04 (Freeze Frame) auf eine 19-02-Anfrage gehört nicht hierher.
        assert_eq!(
            parse_dtc_liste(&[0x59, 0x04, 0xFF]).unwrap_err(),
            UdsFehler::FalscheSubfunktion {
                erwartet: 0x02,
                gesehen: 0x04
            }
        );
    }

    #[test]
    fn dtc_mit_nrc_wird_abgelehnt() {
        let err = parse_dtc_liste(&[0x7F, 0x19, 0x11]).unwrap_err();
        assert_eq!(
            err,
            UdsFehler::Nrc {
                dienst: 0x19,
                code: 0x11
            }
        );
        assert!(err.to_string().contains("serviceNotSupported"), "{err}");
    }

    #[test]
    fn read_dtc_am_simulator() {
        let mut t = Loopback::new(vec![antwort(&[0x59, 0x02, 0xFF, 0x03, 0x01, 0x00, 0x2F])]);
        let liste = UdsClient::new()
            .read_dtc(&mut t, DTC_MASKE_ALLE, 100)
            .unwrap();

        assert_eq!(liste.eintraege.len(), 1);
        assert_eq!(&t.sent()[0].data[..4], &[0x03, 0x19, 0x02, 0xFF]);
    }

    #[test]
    fn read_dtc_wartet_aufschuebe_ab() {
        // Ein Steuergerät, das den Fehlerspeicher erst nach 0x78 liefert.
        let mut t = Loopback::new(vec![
            aufschub(0x19),
            antwort(&[0x59, 0x02, 0xFF, 0xC1, 0x00, 0x1F, 0x2F]),
        ]);
        let liste = UdsClient::new()
            .read_dtc(&mut t, DTC_MASKE_ALLE, 100)
            .unwrap();

        assert_eq!(liste.eintraege.len(), 1);
        assert_eq!(liste.eintraege[0].dtc, [0xC1, 0x00, 0x1F]);
        assert_eq!(t.sent().len(), 1);
    }

    #[test]
    fn lange_dtc_liste_kommt_als_multi_frame() {
        // Fünf Einträge sind 3 + 20 Byte und passen nicht in einen Rahmen:
        // First Frame, Flow Control, drei Consecutive Frames.
        let mut nutzlast = vec![0x59, 0x02, 0xFF];
        for nummer in 0u8..5 {
            nutzlast.extend_from_slice(&[nummer, 0x01, 0x00, 0x2F]);
        }

        let mut ff = vec![0x10u8, nutzlast.len() as u8];
        ff.extend_from_slice(&nutzlast[..6]);
        let mut rahmen = vec![CanFrame::new(RSP_ID, &ff)];
        for (nummer, stueck) in nutzlast[6..].chunks(7).enumerate() {
            let mut cf = vec![0x21 + nummer as u8];
            cf.extend_from_slice(stueck);
            rahmen.push(CanFrame::new(RSP_ID, &cf));
        }

        let mut t = Loopback::new(rahmen);
        let liste = UdsClient::new()
            .read_dtc(&mut t, DTC_MASKE_ALLE, 100)
            .unwrap();

        assert_eq!(liste.eintraege.len(), 5);
        assert_eq!(liste.eintraege[4].dtc, [0x04, 0x01, 0x00]);
        assert_eq!(liste.eintraege[4].status, 0x2F);
    }
}
