#![no_main]
//! UDS-Auswertung mit fremden Bytes.
//!
//! Gefahren werden die Funktionen, die eine Antwort des Steuergeräts lesen und
//! daraus Werte machen: Sitzungswechsel, Kennung lesen, Fehlerspeicher,
//! NRC-Erkennung. Sie sind der Rand, an dem beliebige Bytes zu Strukturen
//! werden — und sie laufen ohne Transport, also ohne Wartezeit im Ziel.
//!
//! Der erste Byte liefert die Subfunktion bzw. das obere Byte der Kennung, das
//! zweite das untere; der Rest ist die Antwort. So trifft der Zufall auch die
//! Fälle, in denen die Antwort zum Aufruf passt.

use libfuzzer_sys::fuzz_target;
use vci_core::uds;

fuzz_target!(|daten: &[u8]| {
    if daten.len() < 2 {
        return;
    }

    let sitzung = daten[0];
    let did = u16::from_be_bytes([daten[0], daten[1]]);
    let antwort = &daten[2..];

    let _ = uds::parse_sitzung(antwort, sitzung);
    let _ = uds::parse_read_did(antwort, did);
    let _ = uds::parse_dtc_liste(antwort);

    let _ = uds::pruefe_nrc(antwort);
    let _ = uds::ist_aufschub(antwort);
    let _ = uds::nrc_text(daten[0]);

    // Der Weg, den die FFI nimmt: ein Transportergebnis wird zur Antwort.
    let _ = uds::aus_transport(Ok(antwort.to_vec()));
});
