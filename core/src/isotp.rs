//! ISO 15765-2 — Segmentierung von Nachrichten über CAN.
//!
//! Verhalten identisch zur C-Implementierung in `j2534/src/transport_usb.c`;
//! die Übereinstimmung wird gegen den Korpus in `tests/corpus/isotp.txt`
//! geprüft, der aus der C-Fassung erzeugt wurde.
//!
//! Adressierung: Anfragen auf `0x7E0` (Tester → Steuergerät), Antworten auf
//! `0x7E8`. Der Flow-Control-Rahmen des Testers geht auf `0x7E0` — auf die
//! Richtung zum Steuergerät, nicht auf dessen Senderichtung.

use crate::transport::{CanFrame, Result, Transport, TransportError, PAD};

/// Anfrage-ID (Tester → Steuergerät).
pub const REQ_ID: u32 = 0x7E0;
/// Antwort-ID (Steuergerät → Tester).
pub const RSP_ID: u32 = 0x7E8;

/// Größte Nutzlast bei kurzer Adressierung (12 Bit Längenfeld).
pub const MAX_PAYLOAD: usize = 0x0FFF;

/// Ein ISO-TP-Kanal. Zustandslos: Blockgröße und Abstand kommen aus dem
/// Flow-Control-Rahmen und werden nicht über Aufrufe hinweg gemerkt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IsotpChannel {
    req_id: u32,
    rsp_id: u32,
}

impl Default for IsotpChannel {
    fn default() -> Self {
        Self::new()
    }
}

impl IsotpChannel {
    pub const fn new() -> Self {
        IsotpChannel {
            req_id: REQ_ID,
            rsp_id: RSP_ID,
        }
    }

    /// Für Fahrzeuge mit anderer Adressierung.
    pub const fn with_ids(req_id: u32, rsp_id: u32) -> Self {
        IsotpChannel { req_id, rsp_id }
    }

    /// Zerlegt `payload` und sendet sie als Single- oder Multi-Frame.
    pub fn send<T: Transport>(&self, t: &mut T, payload: &[u8]) -> Result<()> {
        if payload.is_empty() {
            return Err(TransportError::Protocol("leere Nachricht"));
        }
        if payload.len() > MAX_PAYLOAD {
            return Err(TransportError::Protocol("Nachricht zu lang"));
        }

        if payload.len() <= 7 {
            let mut f = [PAD; 8];
            f[0] = payload.len() as u8; // 0x0L
            f[1..1 + payload.len()].copy_from_slice(payload);
            return t.send(&CanFrame::new(self.req_id, &f));
        }

        // First Frame, danach wartet der Tester auf das Flow-Control des ECU.
        let mut f = [PAD; 8];
        f[0] = 0x10 | ((payload.len() >> 8) as u8 & 0x0F);
        f[1] = (payload.len() & 0xFF) as u8;
        f[2..8].copy_from_slice(&payload[..6]);
        t.send(&CanFrame::new(self.req_id, &f))?;

        let fc = t.recv(1_000)?;
        if fc.data[0] & 0xF0 != 0x30 {
            return Err(TransportError::Protocol("kein Flow-Control-Frame"));
        }
        // Blockgröße und STmin werden gelesen, aber nicht ausgewertet: der
        // Transport wartet selbst, und BS=0 heißt „alles auf einmal".
        let _bs = fc.data[1];
        let _stmin = fc.data[2];

        let mut off = 6;
        let mut seq: u8 = 1;
        while off < payload.len() {
            let chunk = (payload.len() - off).min(7);
            let mut cf = [PAD; 8];
            cf[0] = 0x20 | (seq & 0x0F);
            cf[1..1 + chunk].copy_from_slice(&payload[off..off + chunk]);
            t.send(&CanFrame::new(self.req_id, &cf))?;
            off += chunk;
            seq = (seq + 1) & 0x0F;
        }
        Ok(())
    }

    /// Empfängt eine vollständige Nachricht und setzt sie zusammen.
    pub fn recv<T: Transport>(&self, t: &mut T, timeout_ms: u32) -> Result<Vec<u8>> {
        let f = t.recv(timeout_ms)?;
        match f.data[0] & 0xF0 {
            0x00 => {
                let n = (f.data[0] & 0x0F) as usize;
                if n > f.dlc as usize {
                    return Err(TransportError::Protocol("Single Frame länger als Rahmen"));
                }
                Ok(f.data[1..1 + n].to_vec())
            }
            0x10 => {
                let total = (((f.data[0] & 0x0F) as usize) << 8) | f.data[1] as usize;
                if total < 8 {
                    return Err(TransportError::Protocol("First Frame mit ungültiger Länge"));
                }
                let mut out = vec![0u8; total];
                out[..6].copy_from_slice(&f.data[2..8]);

                // Flow Control: alles auf einmal, kein Mindestabstand.
                let mut fc = [PAD; 8];
                fc[0] = 0x30;
                fc[1] = 0x00;
                fc[2] = 0x00;
                t.send(&CanFrame::new(self.req_id, &fc))?;

                let mut got = 6;
                let mut expect_seq: u8 = 1;
                while got < total {
                    let cf = t.recv(timeout_ms)?;
                    if cf.data[0] & 0xF0 != 0x20 {
                        return Err(TransportError::Protocol("erwarte Consecutive Frame"));
                    }
                    if cf.data[0] & 0x0F != expect_seq & 0x0F {
                        return Err(TransportError::Protocol("ISO-TP Sequenzfehler"));
                    }
                    let chunk = (total - got).min(7);
                    out[got..got + chunk].copy_from_slice(&cf.data[1..1 + chunk]);
                    got += chunk;
                    expect_seq = (expect_seq + 1) & 0x0F;
                }
                Ok(out)
            }
            0x30 => Err(TransportError::Protocol(
                "unerwarteter Flow-Control vom Steuergerät",
            )),
            _ => Err(TransportError::Protocol("unerwarteter ISO-TP-Rahmen")),
        }
    }

    pub fn req_id(&self) -> u32 {
        self.req_id
    }

    pub fn rsp_id(&self) -> u32 {
        self.rsp_id
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::loopback::Loopback;

    #[test]
    fn leere_nachricht_wird_abgelehnt() {
        let mut t = Loopback::empty();
        let err = IsotpChannel::new().send(&mut t, &[]).unwrap_err();
        assert_eq!(err, TransportError::Protocol("leere Nachricht"));
        assert!(t.sent().is_empty());
    }

    #[test]
    fn sequenzfehler_wird_erkannt() {
        // First Frame über 20 Byte, dann ein CF mit falscher Sequenznummer.
        let mut t = Loopback::new(vec![
            CanFrame::new(RSP_ID, &[0x10, 0x14, 0x62, 0xF1, 0x90, 0x11, 0x22, 0x33]),
            CanFrame::new(RSP_ID, &[0x22, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA]),
        ]);
        let err = IsotpChannel::new().recv(&mut t, 100).unwrap_err();
        assert_eq!(err, TransportError::Protocol("ISO-TP Sequenzfehler"));
        // Der Flow-Control-Rahmen ging trotzdem raus, auf die Anfrage-ID.
        assert_eq!(t.sent().len(), 1);
        assert_eq!(t.sent()[0].id, REQ_ID);
        assert_eq!(t.sent()[0].data[0], 0x30);
    }

    #[test]
    fn unbekannter_rahmentyp_wird_abgelehnt() {
        let mut t = Loopback::new(vec![CanFrame::new(RSP_ID, &[0x40, 0, 0, 0, 0, 0, 0, 0])]);
        let err = IsotpChannel::new().recv(&mut t, 100).unwrap_err();
        assert_eq!(err, TransportError::Protocol("unerwarteter ISO-TP-Rahmen"));
    }
}
