//! Konformitätstest gegen den Korpus aus der C-Implementierung.
//!
//! Die Datei `tests/corpus/isotp.txt` wird von `j2534/tools/golden.c` erzeugt
//! (`make -C j2534 corpus`) und enthält die CAN-Rahmen, die die C-Fassung
//! tatsächlich sendet und empfängt. Weicht der Rust-Kern ab, schlägt dieser
//! Test fehl — genau das soll er.

use std::fs;

use vci_core::{CanFrame, IsotpChannel, Loopback};

#[derive(Debug, Default)]
struct Scenario {
    name: String,
    req: Vec<u8>,
    reply: Vec<u8>,
    tx: Vec<CanFrame>,
    flow_in: Vec<CanFrame>,
    flow_out: Vec<CanFrame>,
    rx: Vec<CanFrame>,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02X}")).collect()
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).expect("Hex"))
        .collect()
}

fn frame(id: u32, dlc: u8, data: &[u8]) -> CanFrame {
    let mut d = [vci_core::transport::PAD; 8];
    let n = data.len().min(8);
    d[..n].copy_from_slice(&data[..n]);
    CanFrame { id, data: d, dlc }
}

/// Vergleicht Rahmen als „ID + belegte Bytes", damit Padding-Unterschiede
/// sichtbar werden, ohne vom Feld `data` abzuhängen.
fn frames(fs: &[CanFrame]) -> Vec<String> {
    fs.iter()
        .map(|f| format!("{:03X} {}", f.id, hex(f.payload())))
        .collect()
}

fn load() -> Vec<Scenario> {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/corpus/isotp.txt");
    let text = fs::read_to_string(path).expect("Korpus fehlt — `make -C j2534 corpus`");
    let mut out: Vec<Scenario> = Vec::new();
    let mut cur: Option<Scenario> = None;

    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = line.split_whitespace();
        let role = parts.next().unwrap_or("");
        match role {
            "scenario" => {
                let mut s = Scenario::default();
                s.name = parts.collect::<Vec<_>>().join(" ");
                cur = Some(s);
            }
            "desc" => {}
            "req" => cur.as_mut().unwrap().req = unhex(parts.next().unwrap_or("")),
            "reply" => cur.as_mut().unwrap().reply = unhex(parts.next().unwrap_or("")),
            "result" => {}
            "end" => {
                if let Some(s) = cur.take() {
                    out.push(s);
                }
            }
            "tx" | "flow_in" | "flow_out" | "rx" => {
                let id = u32::from_str_radix(parts.next().unwrap_or("0"), 16).expect("ID");
                let dlc: u8 = parts.next().unwrap_or("0").parse().expect("DLC");
                let data = unhex(parts.next().unwrap_or(""));
                let f = frame(id, dlc, &data);
                let s = cur.as_mut().expect("Rahmen ohne scenario");
                match role {
                    "tx" => s.tx.push(f),
                    "flow_in" => s.flow_in.push(f),
                    "flow_out" => s.flow_out.push(f),
                    _ => s.rx.push(f),
                }
            }
            other => panic!("unbekannte Zeile im Korpus: {other}"),
        }
    }
    out
}

#[test]
fn korpus_ist_vorhanden() {
    let all = load();
    assert!(
        all.len() >= 7,
        "Korpus unerwartet klein ({} Szenarien) — wurde `make -C j2534 corpus` ausgeführt?",
        all.len()
    );
    for s in &all {
        assert!(!s.req.is_empty(), "{}: keine Anfrage", s.name);
        assert!(!s.reply.is_empty(), "{}: keine Antwort", s.name);
        assert!(!s.tx.is_empty(), "{}: keine gesendeten Rahmen", s.name);
    }
}

#[test]
fn empfang_entspricht_der_c_implementierung() {
    let ch = IsotpChannel::new();
    for s in load() {
        let mut t = Loopback::new(s.rx.clone());
        let got = ch
            .recv(&mut t, 500)
            .unwrap_or_else(|e| panic!("{}: Empfang fehlgeschlagen: {e}", s.name));

        assert_eq!(hex(&got), hex(&s.reply), "{}: Nutzlast weicht ab", s.name);
        assert_eq!(
            frames(t.sent()),
            frames(&s.flow_out),
            "{}: Flow-Control-Rahmen weicht ab",
            s.name
        );
    }
}

#[test]
fn senden_entspricht_der_c_implementierung() {
    let ch = IsotpChannel::new();
    for s in load() {
        let mut t = Loopback::new(s.flow_in.clone());
        ch.send(&mut t, &s.req)
            .unwrap_or_else(|e| panic!("{}: Senden fehlgeschlagen: {e}", s.name));

        assert_eq!(
            frames(t.sent()),
            frames(&s.tx),
            "{}: gesendete Rahmen weichen ab",
            s.name
        );
    }
}

#[test]
fn flow_control_geht_an_das_steuergeraet() {
    // Regression: die C-Fassung schickte den Flow-Control-Rahmen auf 0x7E8,
    // also auf die Senderichtung des ECU. Auf echter Hardware bleibt die
    // Übertragung damit nach dem First Frame stehen.
    let ch = IsotpChannel::new();
    let ff = CanFrame::new(0x7E8, &[0x10, 0x14, 0x62, 0xF1, 0x90, 0xAA, 0xAA, 0xAA]);
    let mut t = Loopback::new(vec![ff]);
    let _ = ch.recv(&mut t, 100); // endet mit Timeout, der FC ist aber raus
    assert_eq!(t.sent().len(), 1);
    assert_eq!(t.sent()[0].id, 0x7E0, "Flow Control muss auf 0x7E0 gehen");
}
