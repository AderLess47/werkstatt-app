//! Audit — die Hash-Kette über jede Operation am Fahrzeug.
//!
//! ## Warum eine Kette und keine Liste
//!
//! Ein Protokolleintrag allein beweist nichts: wer ihn schreibt, kann ihn auch
//! ändern. Erst die Verkettung macht Manipulation sichtbar — jeder Eintrag
//! enthält den Hash seines Vorgängers, also bricht jede nachträgliche Änderung
//! alle folgenden Hashes. Was das *nicht* leistet, steht unten unter
//! „Grenzen".
//!
//! ## Warum SHA-256 selbst implementiert
//!
//! Der Kern hat **keine** Abhängigkeiten (siehe `core/Cargo.toml`): eine
//! Krypto-Bibliothek wäre die erste, und sie würde über die FFI-Grenze auf
//! Android und iOS mitgebaut. 120 Zeilen SHA-256 sind überschaubar, und die
//! Umsetzung wird gegen die veröffentlichten Testvektoren aus FIPS 180-4
//! geprüft — nicht gegen sich selbst.
//!
//! ## Grenzen
//!
//! - Die Kette erkennt Änderungen an Einträgen **und** an der Reihenfolge,
//!   solange der Prüfer den letzten Hash kennt. Wer die ganze Kette neu
//!   schreibt, erzeugt eine formal gültige Kette — dagegen hilft nur ein
//!   extern verankerter Hash (Signatur, Zeitstempel-Dienst), und der gehört
//!   nicht in dieses Modul.
//! - Es gibt keine Zeit aus dem System: `zeit_ms` kommt vom Aufrufer. Eine Uhr,
//!   die der Kern selbst liest, wäre auf jeder Plattform eine andere.
//! - Die JSON-Ausgabe ist bewusst ein flaches, eigenes Format (kein serde):
//!   sie ist für Menschen und für den Export gedacht, nicht als Archivformat
//!   mit Schema-Version.

use std::fmt;

/// SHA-256-Rundkonstanten (FIPS 180-4, §4.2.2).
const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

/// Startwerte aus FIPS 180-4, §5.3.3.
const H0: [u32; 8] = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
];

/// SHA-256 über beliebige Bytes.
///
/// Eigene Umsetzung statt Abhängigkeit — Begründung im Modulkopf. Geprüft
/// gegen die FIPS-Vektoren: leerer Text, `abc` und der 56-Zeichen-Vektor, der
/// den Padding-Fall über eine Blockgrenze abdeckt.
pub fn sha256(daten: &[u8]) -> [u8; 32] {
    let mut h = H0;

    // Padding: ein 0x80, dann Nullen bis 56 Byte im letzten Block, dann die
    // Länge in Bit als 64-Bit-Big-Endian.
    let mut nachricht = Vec::with_capacity(daten.len() + 72);
    nachricht.extend_from_slice(daten);
    let bitlaenge = (daten.len() as u64).wrapping_mul(8);
    nachricht.push(0x80);
    while nachricht.len() % 64 != 56 {
        nachricht.push(0);
    }
    nachricht.extend_from_slice(&bitlaenge.to_be_bytes());

    for block in nachricht.chunks_exact(64) {
        let mut w = [0u32; 64];
        for (i, wort) in block.chunks_exact(4).enumerate() {
            w[i] = u32::from_be_bytes([wort[0], wort[1], wort[2], wort[3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }

        let mut a = h[0];
        let mut b = h[1];
        let mut c = h[2];
        let mut d = h[3];
        let mut e = h[4];
        let mut f = h[5];
        let mut g = h[6];
        let mut hh = h[7];

        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);

            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }

        for (ziel, wert) in h.iter_mut().zip([a, b, c, d, e, f, g, hh]) {
            *ziel = ziel.wrapping_add(wert);
        }
    }

    let mut aus = [0u8; 32];
    for (i, wort) in h.iter().enumerate() {
        aus[i * 4..i * 4 + 4].copy_from_slice(&wort.to_be_bytes());
    }
    aus
}

/// Hash als Hex-Kleinbuchstaben — so, wie er in Logs wiederzufinden ist.
pub fn hex32(hash: &[u8; 32]) -> String {
    let mut s = String::with_capacity(64);
    for b in hash {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// Wie eine Operation ausgegangen ist.
///
/// Der Grund steht als Text drin, nicht als Code: er landet im Export und
/// muss dort lesbar sein. Ein Code ohne Tabelle wäre im Prüfbericht wertlos.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ergebnis {
    Erfolg,
    Fehler { grund: String },
}

impl Ergebnis {
    pub fn ist_erfolg(&self) -> bool {
        matches!(self, Ergebnis::Erfolg)
    }

    fn als_text(&self) -> String {
        match self {
            Ergebnis::Erfolg => "Erfolg".to_string(),
            Ergebnis::Fehler { grund } => format!("Fehler: {grund}"),
        }
    }
}

/// Ein Eintrag der Kette.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuditEintrag {
    /// Fortlaufend ab 1 — eine Lücke ist sichtbar, ohne die Hashes zu prüfen.
    pub laufnummer: u32,
    /// Zeitpunkt in Millisekunden, wie ihn der Aufrufer liefert.
    pub zeit_ms: u64,
    /// Fahrzeugidentifikation; leer, wenn sie nicht gelesen werden konnte.
    pub vin: String,
    /// Adresse des angesprochenen Steuergeräts (z. B. `0x7E0`).
    pub steuergeraet: u32,
    /// Ausgeführter Dienst, Klartext plus Kennung (z. B. `routine 31 01 C0 08`).
    pub dienst: String,
    /// Ergebnis der Operation.
    pub ergebnis: Ergebnis,
    /// Hash des Vorgängers; bei Eintrag 1 der Startwert der Kette.
    pub vorheriger_hash: [u8; 32],
    /// Hash über diesen Eintrag samt `vorheriger_hash`.
    pub hash: [u8; 32],
}

impl AuditEintrag {
    /// Die Bytes, über die gehasht wird.
    ///
    /// Jedes Feld bekommt seine Länge vorangestellt: ohne das wären
    /// `("ab", "c")` und `("a", "bc")` derselbe Hash-Eingang, und zwei
    /// verschiedene Vorgänge ließen sich gegeneinander austauschen.
    fn kanonisch(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(128);
        out.extend_from_slice(&self.laufnummer.to_be_bytes());
        out.extend_from_slice(&self.zeit_ms.to_be_bytes());
        out.extend_from_slice(&self.steuergeraet.to_be_bytes());
        for text in [&self.vin, &self.dienst, &self.ergebnis.als_text()] {
            out.extend_from_slice(&(text.len() as u32).to_be_bytes());
            out.extend_from_slice(text.as_bytes());
        }
        out.extend_from_slice(&self.vorheriger_hash);
        out
    }

    /// Rechnet den Hash neu — für die Prüfung nach einer Änderung.
    pub fn neu_berechnet(&self) -> [u8; 32] {
        sha256(&self.kanonisch())
    }
}

/// Fehler beim Anhängen oder Prüfen der Kette.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuditFehler {
    /// Laufnummer passt nicht zur Position (Eintrag entfernt oder getauscht).
    Laufnummer {
        stelle: usize,
        erwartet: u32,
        gefunden: u32,
    },
    /// `vorheriger_hash` zeigt nicht auf den Vorgänger.
    KetteGebrochen { stelle: usize },
    /// Der gespeicherte Hash passt nicht zum Inhalt.
    HashPasstNicht { stelle: usize },
}

impl fmt::Display for AuditFehler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AuditFehler::Laufnummer {
                stelle,
                erwartet,
                gefunden,
            } => write!(
                f,
                "Laufnummer an Stelle {stelle} ist {gefunden}, erwartet {erwartet} — ein Eintrag fehlt oder wurde getauscht"
            ),
            AuditFehler::KetteGebrochen { stelle } => write!(
                f,
                "Hash-Kette an Stelle {stelle} gebrochen: vorheriger_hash zeigt nicht auf den Vorgänger"
            ),
            AuditFehler::HashPasstNicht { stelle } => write!(
                f,
                "Hash an Stelle {stelle} passt nicht zum Inhalt — der Eintrag wurde nachträglich geändert"
            ),
        }
    }
}

impl std::error::Error for AuditFehler {}

/// Die Kette selbst.
///
/// `start` ist der Hash „vor dem ersten Eintrag". Er ist konstant, damit eine
/// leere Kette und eine neu aufgebaute Kette denselben Anfang haben; wer
/// Fälschung erkennen will, muss den **letzten** Hash extern verankern.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AuditKette {
    eintraege: Vec<AuditEintrag>,
}

impl AuditKette {
    /// Hash vor dem ersten Eintrag (SHA-256 über die leere Eingabe).
    pub fn start_hash() -> [u8; 32] {
        sha256(b"")
    }

    pub fn neu() -> Self {
        AuditKette {
            eintraege: Vec::new(),
        }
    }

    pub fn eintraege(&self) -> &[AuditEintrag] {
        &self.eintraege
    }

    pub fn len(&self) -> usize {
        self.eintraege.len()
    }

    pub fn is_empty(&self) -> bool {
        self.eintraege.is_empty()
    }

    /// Der Hash des letzten Eintrags — der Wert, der extern verankert gehört.
    pub fn letzter_hash(&self) -> [u8; 32] {
        self.eintraege
            .last()
            .map(|e| e.hash)
            .unwrap_or_else(AuditKette::start_hash)
    }

    /// Hängt einen Eintrag an und gibt seine Laufnummer zurück.
    ///
    /// Der Hash wird hier gerechnet, nie vom Aufrufer übernommen: sonst könnte
    /// ein Aufrufer eine Kette schreiben, die in sich stimmt, aber nicht zum
    /// Vorgang passt.
    pub fn anhaengen(
        &mut self,
        zeit_ms: u64,
        vin: &str,
        steuergeraet: u32,
        dienst: &str,
        ergebnis: Ergebnis,
    ) -> u32 {
        let laufnummer = self.eintraege.len() as u32 + 1;
        let mut eintrag = AuditEintrag {
            laufnummer,
            zeit_ms,
            vin: vin.to_string(),
            steuergeraet,
            dienst: dienst.to_string(),
            ergebnis,
            vorheriger_hash: self.letzter_hash(),
            hash: [0u8; 32],
        };
        eintrag.hash = eintrag.neu_berechnet();
        self.eintraege.push(eintrag);
        laufnummer
    }

    /// Prüft Laufnummern, Verkettung und jeden Hash.
    pub fn pruefen(&self) -> std::result::Result<(), AuditFehler> {
        let mut vorheriger = AuditKette::start_hash();
        for (stelle, eintrag) in self.eintraege.iter().enumerate() {
            let erwartet = stelle as u32 + 1;
            if eintrag.laufnummer != erwartet {
                return Err(AuditFehler::Laufnummer {
                    stelle,
                    erwartet,
                    gefunden: eintrag.laufnummer,
                });
            }
            if eintrag.vorheriger_hash != vorheriger {
                return Err(AuditFehler::KetteGebrochen { stelle });
            }
            if eintrag.neu_berechnet() != eintrag.hash {
                return Err(AuditFehler::HashPasstNicht { stelle });
            }
            vorheriger = eintrag.hash;
        }
        Ok(())
    }

    /// Export als JSON-Zeilenobjekt je Eintrag.
    ///
    /// Absichtlich flach und ohne Bibliothek: der Export soll auch dann lesbar
    /// sein, wenn er mit `grep` geprüft wird. Zeichenketten werden maskiert,
    /// damit ein VIN mit Anführungszeichen den Export nicht zerlegt.
    pub fn als_json(&self) -> String {
        let mut s = String::from("{\n  \"eintraege\": [\n");
        for (i, e) in self.eintraege.iter().enumerate() {
            s.push_str("    {");
            s.push_str(&format!("\"laufnummer\": {},", e.laufnummer));
            s.push_str(&format!("\"zeit_ms\": {},", e.zeit_ms));
            s.push_str(&format!("\"vin\": {},", json_text(&e.vin)));
            s.push_str(&format!("\"steuergeraet\": {},", e.steuergeraet));
            s.push_str(&format!("\"dienst\": {},", json_text(&e.dienst)));
            s.push_str(&format!(
                "\"ergebnis\": {},",
                json_text(&e.ergebnis.als_text())
            ));
            s.push_str(&format!(
                "\"vorheriger_hash\": \"{}\",",
                hex32(&e.vorheriger_hash)
            ));
            s.push_str(&format!("\"hash\": \"{}\"", hex32(&e.hash)));
            s.push('}');
            if i + 1 < self.eintraege.len() {
                s.push(',');
            }
            s.push('\n');
        }
        s.push_str("  ],\n");
        s.push_str(&format!(
            "  \"letzter_hash\": \"{}\"\n}}\n",
            hex32(&self.letzter_hash())
        ));
        s
    }
}

/// Maskiert eine Zeichenkette für JSON.
///
/// Steuerzeichen unter 0x20 werden als `\u00XX` geschrieben; alles andere
/// bleibt, weil UTF-8 in JSON erlaubt ist und die Umlaute sonst unlesbar
/// würden.
fn json_text(text: &str) -> String {
    let mut s = String::with_capacity(text.len() + 2);
    s.push('"');
    for zeichen in text.chars() {
        match zeichen {
            '"' => s.push_str("\\\""),
            '\\' => s.push_str("\\\\"),
            '\n' => s.push_str("\\n"),
            '\r' => s.push_str("\\r"),
            '\t' => s.push_str("\\t"),
            c if (c as u32) < 0x20 => s.push_str(&format!("\\u{:04x}", c as u32)),
            c => s.push(c),
        }
    }
    s.push('"');
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    // --- SHA-256 gegen die veröffentlichten Vektoren ----------------------

    #[test]
    fn sha256_lehre_ist_der_bekannte_wert() {
        assert_eq!(
            hex(&sha256(b"")),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn sha256_von_abc_ist_der_bekannte_wert() {
        assert_eq!(
            hex(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn sha256_ueber_eine_blockgrenze() {
        // 56 Byte: der Padding-Fall, bei dem die Länge in einen zweiten Block
        // rutscht — genau da gehen selbstgebaute Umsetzungen kaputt.
        assert_eq!(
            hex(&sha256(
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            )),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
    }

    #[test]
    fn sha256_von_einer_million_a() {
        // Geprüft wird nur der Anfang und das Ende: der Vektor lautet
        // cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0.
        let million = vec![b'a'; 1_000_000];
        assert_eq!(
            hex(&sha256(&million)),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        );
    }

    // --- Kette ------------------------------------------------------------

    #[test]
    fn leere_kette_ist_gueltig() {
        let kette = AuditKette::neu();
        assert!(kette.pruefen().is_ok());
        assert_eq!(kette.letzter_hash(), AuditKette::start_hash());
        assert!(kette.is_empty());
    }

    #[test]
    fn erste_laufnummer_ist_eins_und_zeigt_auf_den_start() {
        let mut kette = AuditKette::neu();
        let nr = kette.anhaengen(
            1_000,
            "WVWZZZ1JZXW000001",
            0x7E0,
            "status",
            Ergebnis::Erfolg,
        );

        assert_eq!(nr, 1);
        assert_eq!(kette.eintraege()[0].laufnummer, 1);
        assert_eq!(
            kette.eintraege()[0].vorheriger_hash,
            AuditKette::start_hash()
        );
        assert!(kette.pruefen().is_ok());
    }

    #[test]
    fn zweiter_eintrag_haengt_am_ersten() {
        let mut kette = AuditKette::neu();
        kette.anhaengen(1, "VIN", 0x7E0, "a", Ergebnis::Erfolg);
        let erster_hash = kette.eintraege()[0].hash;
        kette.anhaengen(2, "VIN", 0x7E0, "b", Ergebnis::Erfolg);

        assert_eq!(kette.eintraege()[1].vorheriger_hash, erster_hash);
        assert_eq!(kette.letzter_hash(), kette.eintraege()[1].hash);
        assert!(kette.pruefen().is_ok());
    }

    #[test]
    fn nachtraegliche_aenderung_am_ergebnis_faellt_auf() {
        let mut kette = AuditKette::neu();
        kette.anhaengen(1, "VIN", 0x7E0, "a", Ergebnis::Erfolg);
        kette.anhaengen(2, "VIN", 0x7E0, "b", Ergebnis::Erfolg);

        // Aus einem Fehler einen Erfolg machen — der klassische Fall.
        kette.eintraege[0].ergebnis = Ergebnis::Fehler {
            grund: "abgebrochen".to_string(),
        };

        assert_eq!(
            kette.pruefen(),
            Err(AuditFehler::HashPasstNicht { stelle: 0 })
        );
    }

    #[test]
    fn entferntes_mitglied_faellt_auf() {
        let mut kette = AuditKette::neu();
        kette.anhaengen(1, "VIN", 0x7E0, "a", Ergebnis::Erfolg);
        kette.anhaengen(2, "VIN", 0x7E0, "b", Ergebnis::Erfolg);
        kette.anhaengen(3, "VIN", 0x7E0, "c", Ergebnis::Erfolg);

        kette.eintraege.remove(1);

        assert_eq!(
            kette.pruefen(),
            Err(AuditFehler::Laufnummer {
                stelle: 1,
                erwartet: 2,
                gefunden: 3
            })
        );
    }

    #[test]
    fn getauschte_eintraege_fallen_auf() {
        let mut kette = AuditKette::neu();
        kette.anhaengen(1, "VIN", 0x7E0, "a", Ergebnis::Erfolg);
        kette.anhaengen(2, "VIN", 0x7E0, "b", Ergebnis::Erfolg);

        kette.eintraege.swap(0, 1);

        assert_eq!(
            kette.pruefen(),
            Err(AuditFehler::Laufnummer {
                stelle: 0,
                erwartet: 1,
                gefunden: 2
            })
        );
    }

    #[test]
    fn kanonische_form_trennt_felder_eindeutig() {
        // Ohne Längenpräfix wären ("ab","c") und ("a","bc") gleich — hier
        // müssen sich die Hashes unterscheiden.
        let mut a = AuditKette::neu();
        a.anhaengen(1, "ab", 0x7E0, "c", Ergebnis::Erfolg);
        let mut b = AuditKette::neu();
        b.anhaengen(1, "a", 0x7E0, "bc", Ergebnis::Erfolg);

        assert_ne!(a.eintraege()[0].hash, b.eintraege()[0].hash);
    }

    #[test]
    fn json_enthaelt_alle_felder_und_den_letzten_hash() {
        let mut kette = AuditKette::neu();
        kette.anhaengen(
            1_700,
            "WVWZZZ1JZXW000001",
            0x7E0,
            "routine 31 01 C0 08",
            Ergebnis::Erfolg,
        );

        let json = kette.als_json();

        assert!(json.contains("\"laufnummer\": 1"));
        assert!(json.contains("\"zeit_ms\": 1700"));
        assert!(json.contains("\"vin\": \"WVWZZZ1JZXW000001\""));
        assert!(json.contains("\"dienst\": \"routine 31 01 C0 08\""));
        assert!(json.contains("\"ergebnis\": \"Erfolg\""));
        assert!(json.contains(&format!(
            "\"letzter_hash\": \"{}\"",
            hex32(&kette.letzter_hash())
        )));
    }

    #[test]
    fn json_maskiert_anfuehrungszeichen_und_steuerzeichen() {
        let mut kette = AuditKette::neu();
        kette.anhaengen(1, "V\"IN\n", 0x7E0, "a\\b", Ergebnis::Erfolg);

        let json = kette.als_json();

        assert!(json.contains("\"vin\": \"V\\\"IN\\n\""));
        assert!(json.contains("\"dienst\": \"a\\\\b\""));
    }

    #[test]
    fn fehler_im_eintrag_wird_benannt() {
        let eintrag = AuditEintrag {
            laufnummer: 1,
            zeit_ms: 0,
            vin: String::new(),
            steuergeraet: 0,
            dienst: String::new(),
            ergebnis: Ergebnis::Fehler {
                grund: "Timeout".to_string(),
            },
            vorheriger_hash: AuditKette::start_hash(),
            hash: [0u8; 32],
        };
        assert!(!eintrag.ergebnis.ist_erfolg());
        assert_eq!(eintrag.ergebnis.als_text(), "Fehler: Timeout");
    }
}
