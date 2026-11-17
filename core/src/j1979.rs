//! SAE J2012 / ISO 15031-6 — Fehlercodes lesbar machen.
//!
//! [`crate::uds`] liefert den Fehlerspeicher so, wie das Steuergerät ihn
//! schickt: drei Byte Fehlercode, ein Byte Status. Hier wird daraus, was auf
//! dem Bildschirm stehen soll — und **nur** das.
//!
//! Die Fehlerart (drittes Byte, `failureType`) bleibt eine Zahl. Welcher Wert
//! welche Ursache bedeutet, steht in herstellerspezifischen Listen; öffentlich
//! belegbar ist hier nichts. Ein geratener Klartext wäre schlimmer als `-56`,
//! weil er wie Wissen aussieht.
//!
//! Was dieses Modul bewusst nicht tut: Freeze Frames (`19 04`) und Live-Daten
//! auswerten. Beides braucht Antwortlängen, die noch niemand aufgezeichnet hat.

use crate::uds::{DtcEintrag, DtcListe};

/// Das System, zu dem ein Fehlercode gehört — die zwei oberen Bit.
///
/// `P` Antriebsstrang, `C` Fahrwerk, `B` Karosserie, `U` Netzwerk. Sowohl die
/// Zuordnung als auch die Buchstaben sind Konvention (SAE J2012), keine
/// Ableitung aus dem Protokoll.
pub fn system(dtc: &[u8; 3]) -> char {
    match dtc[0] >> 6 {
        0b00 => 'P',
        0b01 => 'C',
        0b10 => 'B',
        _ => 'U',
    }
}

/// Der Fehlercode als vier Zeichen nach dem Buchstaben, z. B. `P0301`.
///
/// Nach dem Buchstaben folgt **eine** Ziffer `0..3` (die restlichen zwei Bit
/// der ersten Nutzbits) und dann drei Hexziffern. Daher sieht `P1234`
/// vierstellig aus, obwohl die erste Stelle nur vier Werte kennt: 2 + 2 + 12
/// Bit, nicht 4 × 4 Bit.
pub fn code_text(dtc: &[u8; 3]) -> String {
    let wert = u16::from_be_bytes([dtc[0], dtc[1]]) & 0x3FFF;
    format!("{}{}{:03X}", system(dtc), (wert >> 12) & 0x3, wert & 0x0FFF)
}

// Die acht Statusbit nach ISO 14229-1, `DTCStatus`. Die Reihenfolge ist die
// Bitreihenfolge — so erscheint der Text später in derselben Ordnung wie die
// Rohzahl, und niemand muss zwei Sortierungen nebeneinander pflegen.
pub const STATUS_TEST_FAILED: u8 = 0x01;
pub const STATUS_TEST_FAILED_ZYKLUS: u8 = 0x02;
pub const STATUS_AUSSTEHEND: u8 = 0x04;
pub const STATUS_BESTAETIGT: u8 = 0x08;
pub const STATUS_TEST_NICHT_ABGESCHLOSSEN: u8 = 0x10;
pub const STATUS_FEHLERHAFT_SEIT_LOESCHEN: u8 = 0x20;
pub const STATUS_ZYKLUS_NICHT_ABGESCHLOSSEN: u8 = 0x40;
pub const STATUS_WARNLEUCHTE: u8 = 0x80;

/// Die gesetzten Statusbit als Wörter, in Bitreihenfolge.
///
/// Wörter statt Kürzel, weil in der Werkstatt jemand mit Handschuhen davor
/// steht: `0x08` erklärt nichts, „bestätigt“ schon.
pub fn status_flags(status: u8) -> Vec<&'static str> {
    const BITS: [(u8, &str); 8] = [
        (STATUS_TEST_FAILED, "Test fehlgeschlagen"),
        (STATUS_TEST_FAILED_ZYKLUS, "in diesem Zyklus fehlgeschlagen"),
        (STATUS_AUSSTEHEND, "ausstehend"),
        (STATUS_BESTAETIGT, "bestätigt"),
        (STATUS_TEST_NICHT_ABGESCHLOSSEN, "Test nicht abgeschlossen"),
        (
            STATUS_FEHLERHAFT_SEIT_LOESCHEN,
            "seit dem Löschen fehlerhaft",
        ),
        (
            STATUS_ZYKLUS_NICHT_ABGESCHLOSSEN,
            "Zyklus nicht abgeschlossen",
        ),
        (STATUS_WARNLEUCHTE, "Warnleuchte angefordert"),
    ];
    BITS.iter()
        .filter(|(bit, _)| status & bit != 0)
        .map(|(_, wort)| *wort)
        .collect()
}

/// Statusbit, die gesetzt sind, obwohl das Steuergerät sie als nicht belegt
/// gemeldet hat.
///
/// Das ist kein Fehler des Testers, sondern ein Widerspruch im Steuergerät —
/// und deshalb sichtbar zu machen statt wegzumaskieren. Wer hier stillschweigend
/// `& verfuegbarkeit` rechnet, verliert genau die Information, die auf einen
/// Firmware-Fehler hindeutet.
pub fn unbelegte_bits(status: u8, verfuegbarkeit: u8) -> u8 {
    status & !verfuegbarkeit
}

/// Ein dekodierter Fehlereintrag, so wie er angezeigt wird.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DtcAnzeige {
    /// Der Code, z. B. `P0301`.
    pub code: String,
    /// Die Fehlerart, roh — siehe Modulbeschreibung.
    pub fehlerart: u8,
    /// Das Statusbyte, roh.
    pub status: u8,
    /// Die gesetzten Statusbit als Wörter.
    pub flags: Vec<&'static str>,
    /// Gesetzte Bit, die das Steuergerät nicht führt; `0`, wenn nichts auffällt.
    pub unbelegt: u8,
}

impl DtcAnzeige {
    /// Eine Zeile für die Anzeige: `P0301-56 (bestätigt)`.
    ///
    /// Die Fehlerart steht als Hexzahl davor, damit sie nicht mit dem Code
    /// verschmilzt — `P030156` wäre nicht mehr auseinanderzunehmen.
    pub fn text(&self) -> String {
        let mut s = format!("{}-{:02X}", self.code, self.fehlerart);
        if self.flags.is_empty() {
            s.push_str(" (kein Statusbit gesetzt)");
        } else {
            s.push_str(&format!(" ({})", self.flags.join(", ")));
        }
        if self.unbelegt != 0 {
            s.push_str(&format!(" [nicht belegte Bit: {:02X}]", self.unbelegt));
        }
        s
    }
}

/// Fehler beim Dekodieren eines einzelnen Eintrags.
///
/// Es gibt genau einen Fall: zu wenig Byte. Alles andere ist nicht falsch,
/// sondern unbekannt — und Unbekanntes wird angezeigt, nicht abgelehnt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum J1979Fehler {
    /// Der Puffer war kürzer als ein Fehlercode.
    ZuKurz {
        /// So viele Byte kamen.
        gelesen: usize,
        /// So viele braucht ein Eintrag.
        mindestens: usize,
    },
}

impl std::fmt::Display for J1979Fehler {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            J1979Fehler::ZuKurz {
                gelesen,
                mindestens,
            } => {
                write!(f, "zu kurz: {gelesen} von {mindestens} Byte")
            }
        }
    }
}

impl std::error::Error for J1979Fehler {}

/// Dekodiert einen Eintrag aus dem Fehlerspeicher.
///
/// `verfuegbarkeit` ist die Statusmaske aus derselben Antwort (`DtcListe::
/// status_verfuegbarkeit`). Ohne sie lässt sich nicht sagen, ob ein gesetztes
/// Bit belegt ist — dann bleibt `unbelegt` bei `0` und niemand behauptet etwas.
pub fn dekodiere(eintrag: &DtcEintrag, verfuegbarkeit: Option<u8>) -> DtcAnzeige {
    DtcAnzeige {
        code: code_text(&eintrag.dtc),
        fehlerart: eintrag.dtc[2],
        status: eintrag.status,
        flags: status_flags(eintrag.status),
        unbelegt: verfuegbarkeit.map_or(0, |maske| unbelegte_bits(eintrag.status, maske)),
    }
}

/// Dekodiert eine ganze Antwort in Anzeigereihenfolge.
pub fn dekodiere_liste(liste: &DtcListe) -> Vec<DtcAnzeige> {
    liste
        .eintraege
        .iter()
        .map(|e| dekodiere(e, Some(liste.status_verfuegbarkeit)))
        .collect()
}

/// Dekodiert aus einem Puffer, der auch abgeschnitten sein darf.
///
/// Der Weg über `&[u8]` existiert für Aufrufer, die keine fertige [`DtcListe`]
/// haben — etwa eine gekürzte Aufzeichnung oder eine FFI-Grenze. Genau dort
/// entstehen halbe Einträge; sie werden als Fehler gemeldet statt mit
/// aufgefüllten Nullen zu einem erfundenen Code zu werden.
pub fn dekodiere_roh(
    bytes: &[u8],
    verfuegbarkeit: Option<u8>,
) -> std::result::Result<DtcAnzeige, J1979Fehler> {
    const EINTRAG: usize = 3;
    if bytes.len() < EINTRAG {
        return Err(J1979Fehler::ZuKurz {
            gelesen: bytes.len(),
            mindestens: EINTRAG,
        });
    }
    Ok(dekodiere(
        &DtcEintrag {
            dtc: [bytes[0], bytes[1], bytes[2]],
            // Ohne Statusbyte gibt es keinen Status — `0` heißt „kein Bit
            // gesetzt“ und nicht „unbekannt“; deshalb verlangt der Aufrufer
            // für den Status den Eintrag, nicht den Rohpuffer.
            status: 0,
        },
        verfuegbarkeit,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eintrag(bytes: [u8; 3], status: u8) -> DtcEintrag {
        DtcEintrag { dtc: bytes, status }
    }

    // --- Buchstabe --------------------------------------------------------

    #[test]
    fn buchstabe_kommt_aus_den_oberen_zwei_bit() {
        assert_eq!(system(&[0x00, 0x00, 0x00]), 'P');
        assert_eq!(system(&[0x40, 0x00, 0x00]), 'C');
        assert_eq!(system(&[0x80, 0x00, 0x00]), 'B');
        assert_eq!(system(&[0xC0, 0x00, 0x00]), 'U');
    }

    #[test]
    fn buchstabe_haengt_nicht_an_den_unteren_bit() {
        // 0x3F und 0x7F unterscheiden sich nur unterhalb der zwei oberen Bit.
        assert_eq!(system(&[0x03, 0x01, 0x00]), 'P');
        assert_eq!(system(&[0x43, 0x01, 0x00]), 'C');
    }

    #[test]
    fn bekannter_code_wird_vierstellig() {
        assert_eq!(code_text(&[0x03, 0x01, 0x56]), "P0301");
        assert_eq!(code_text(&[0x00, 0x00, 0x00]), "P0000");
    }

    #[test]
    fn erste_ziffer_kennt_nur_null_bis_drei() {
        // 0x3000: die zwei Bit vor den drei Hexziffern sind 11 → Ziffer 3.
        assert_eq!(code_text(&[0x30, 0x00, 0x00]), "P3000");
        // 0x0FFF bleibt bei Ziffer 0 und vollem Rest.
        assert_eq!(code_text(&[0x0F, 0xFF, 0x00]), "P0FFF");
    }

    #[test]
    fn code_und_buchstabe_passen_zusammen() {
        // 0xD123 → U, Rest 0x1123 → Ziffer 1, dann 123.
        assert_eq!(code_text(&[0xD1, 0x23, 0x00]), "U1123");
    }

    // --- Statusbit --------------------------------------------------------

    #[test]
    fn status_null_hat_keine_flags() {
        assert!(status_flags(0x00).is_empty());
    }

    #[test]
    fn flags_stehen_in_bitreihenfolge() {
        assert_eq!(status_flags(0x09), vec!["Test fehlgeschlagen", "bestätigt"]);
    }

    #[test]
    fn alle_acht_bit_werden_benannt() {
        // Kein Bit darf stillschweigend verschwinden: acht gesetzt, acht Wörter.
        assert_eq!(status_flags(0xFF).len(), 8);
    }

    #[test]
    fn bestaetigt_ist_bit_drei() {
        assert_eq!(status_flags(STATUS_BESTAETIGT), vec!["bestätigt"]);
        assert_eq!(STATUS_BESTAETIGT, 0x08);
    }

    // --- unbekannte Bit ---------------------------------------------------

    #[test]
    fn gesetztes_bit_ohne_beleg_wird_gemeldet() {
        // Steuergerät führt nur die unteren sieben Bit, setzt aber das obere.
        assert_eq!(unbelegte_bits(0x80, 0x7F), 0x80);
        assert_eq!(unbelegte_bits(0x7F, 0x7F), 0x00);
    }

    #[test]
    fn unbekanntes_bit_verschwindet_nicht_aus_den_flags() {
        let a = dekodiere(&eintrag([0x03, 0x01, 0x00], 0x80), Some(0x7F));
        assert_eq!(a.unbelegt, 0x80);
        assert_eq!(a.flags, vec!["Warnleuchte angefordert"]);
        assert!(a.text().contains("nicht belegte Bit: 80"));
    }

    #[test]
    fn ohne_maske_wird_nichts_behauptet() {
        let a = dekodiere(&eintrag([0x03, 0x01, 0x00], 0x80), None);
        assert_eq!(a.unbelegt, 0);
        assert_eq!(a.flags, vec!["Warnleuchte angefordert"]);
    }

    #[test]
    fn liste_nutzt_die_maske_aus_der_antwort() {
        let liste = DtcListe {
            status_verfuegbarkeit: 0x08,
            eintraege: vec![eintrag([0x03, 0x01, 0x00], 0x09)],
        };
        let anzeigen = dekodiere_liste(&liste);
        assert_eq!(anzeigen.len(), 1);
        assert_eq!(anzeigen[0].unbelegt, 0x01);
        assert_eq!(anzeigen[0].code, "P0301");
    }

    // --- abgeschnittene Antworten ----------------------------------------

    #[test]
    fn leerer_puffer_ist_zu_kurz() {
        assert_eq!(
            dekodiere_roh(&[], None),
            Err(J1979Fehler::ZuKurz {
                gelesen: 0,
                mindestens: 3
            })
        );
    }

    #[test]
    fn halber_eintrag_wird_nicht_zu_einem_code() {
        // Zwei Byte: der Rest würde sonst als 0 ergänzt und ergäbe P0300 —
        // ein Fehlercode, den niemand geschickt hat.
        assert_eq!(
            dekodiere_roh(&[0x03, 0x01], None),
            Err(J1979Fehler::ZuKurz {
                gelesen: 2,
                mindestens: 3
            })
        );
    }

    #[test]
    fn drei_byte_genuegen() {
        let a = dekodiere_roh(&[0x03, 0x01, 0x56], Some(0xFF)).unwrap();
        assert_eq!(a.code, "P0301");
        assert_eq!(a.fehlerart, 0x56);
    }

    #[test]
    fn fehler_hat_einen_text() {
        let e = J1979Fehler::ZuKurz {
            gelesen: 2,
            mindestens: 3,
        };
        assert_eq!(e.to_string(), "zu kurz: 2 von 3 Byte");
    }

    // --- Anzeigetext ------------------------------------------------------

    #[test]
    fn text_trennt_code_und_fehlerart() {
        let a = dekodiere(&eintrag([0x03, 0x01, 0x56], 0x08), Some(0xFF));
        assert_eq!(a.text(), "P0301-56 (bestätigt)");
    }

    #[test]
    fn text_ohne_flags_sagt_das_statt_nichts() {
        let a = dekodiere(&eintrag([0x03, 0x01, 0x00], 0x00), Some(0xFF));
        assert_eq!(a.text(), "P0301-00 (kein Statusbit gesetzt)");
    }

    #[test]
    fn reihenfolge_der_liste_bleibt_erhalten() {
        let liste = DtcListe {
            status_verfuegbarkeit: 0xFF,
            eintraege: vec![
                eintrag([0x03, 0x01, 0x00], 0x08),
                eintrag([0xC1, 0x23, 0x00], 0x04),
            ],
        };
        let codes: Vec<String> = dekodiere_liste(&liste)
            .into_iter()
            .map(|a| a.code)
            .collect();
        // 0xC123: Buchstabe U, Rest 0x0123 → Ziffer 0, dann 123.
        assert_eq!(codes, vec!["P0301", "U0123"]);
    }
}
