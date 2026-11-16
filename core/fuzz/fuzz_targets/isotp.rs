#![no_main]
//! ISO-TP mit fremden Bytes: beliebige Rahmen hinein, beide Richtungen.
//!
//! Der Parser bekommt im Feld Bytes von Steuergeräten, deren Firmware niemand
//! hier kennt. Deshalb zwei Wege: `recv` wertet zusammen, was ein Gerät
//! schickt (Single Frame, First Frame mit Längenfeld, Consecutive Frames mit
//! Sequenznummern), `send` antwortet auf dessen Flow-Control.
//!
//! `dlc` wird absichtlich auch falsch gesetzt: über die FFI kommt der Wert aus
//! der Plattform und darf nicht in einen Indexfehler laufen — dafür gibt es
//! einen Test, hier wird es millionenfach durchprobiert.
//!
//! Zeitlupen sind verboten: der Gegenspieler unten antwortet immer mit
//! `STmin 0`. Mit einem echten STmin würde jeder Rahmen bis zu 127 ms schlafen
//! und der Lauf käme nicht vom Fleck.

use libfuzzer_sys::fuzz_target;
use vci_core::isotp::{IsotpChannel, RSP_ID};
use vci_core::loopback::Loopback;
use vci_core::transport::{CanFrame, Result, Transport};

/// Ein Steuergerät, das jedes Flow-Control sofort mit „weiter" beantwortet.
struct Weiter {
    blockgroesse: u8,
}

impl Transport for Weiter {
    fn send(&mut self, _rahmen: &CanFrame) -> Result<()> {
        Ok(())
    }

    fn recv(&mut self, _timeout_ms: u32) -> Result<CanFrame> {
        Ok(CanFrame::new(RSP_ID, &[0x30, self.blockgroesse, 0x00]))
    }
}

fuzz_target!(|daten: &[u8]| {
    if daten.is_empty() {
        return;
    }
    let modus = daten[0] % 3;
    let rest = &daten[1..];

    match modus {
        // 0/1: Empfangsweg. Der Byte-Strom wird in Acht-Byte-Rahmen zerlegt,
        // wie sie ein Bus liefert; das letzte Stück bleibt kurz.
        0 | 1 => {
            let mut rahmen = Vec::new();
            for stueck in rest.chunks(8) {
                let mut f = CanFrame::new(RSP_ID, stueck);
                if modus == 1 {
                    // Dasselbe Byte für alle Rahmen: so entstehen auch die
                    // Fälle dlc = 0 und dlc > 8 systematisch.
                    f.dlc = rest.first().copied().unwrap_or(0) % 16;
                }
                rahmen.push(f);
            }
            let mut transport = Loopback::new(rahmen);
            let _ = IsotpChannel::new().recv(&mut transport, 0);
        }
        // 2: Sendeweg. Nutzlast bis 300 Byte (ein SFD-Token hat 324 — der
        // Rand interessiert mehr als die Länge), Blockgröße aus den Daten.
        _ => {
            let nutzlast = &rest[..rest.len().min(300)];
            if nutzlast.is_empty() {
                return;
            }
            let mut transport = Weiter {
                blockgroesse: rest.first().copied().unwrap_or(0) % 9,
            };
            let _ = IsotpChannel::new().send(&mut transport, nutzlast);
        }
    }
});
