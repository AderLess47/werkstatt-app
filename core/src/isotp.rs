//! ISO 15765-2 — Segmentierung von Nachrichten über CAN.
//!
//! Verhalten identisch zur C-Implementierung in `j2534/src/transport_usb.c`;
//! die Übereinstimmung wird gegen den Korpus in `tests/corpus/isotp.txt`
//! geprüft, der aus der C-Fassung erzeugt wurde.
//!
//! Adressierung: Anfragen auf `0x7E0` (Tester → Steuergerät), Antworten auf
//! `0x7E8`. Der Flow-Control-Rahmen des Testers geht auf `0x7E0` — auf die
//! Richtung zum Steuergerät, nicht auf dessen Senderichtung.

use std::thread;
use std::time::Duration;

use crate::transport::{CanFrame, Result, Transport, TransportError, PAD};

/// Anfrage-ID (Tester → Steuergerät).
pub const REQ_ID: u32 = 0x7E0;
/// Antwort-ID (Steuergerät → Tester).
pub const RSP_ID: u32 = 0x7E8;

/// Größte Nutzlast bei kurzer Adressierung (12 Bit Längenfeld).
pub const MAX_PAYLOAD: usize = 0x0FFF;

/// Höchstzahl an „wait"-Flow-Control-Rahmen (`0x31`) je Block.
///
/// Ein Steuergerät darf den Tester mehrfach vertrösten; ohne Grenze würde der
/// Sender bei einem defekten Gegenüber unendlich warten.
pub const MAX_FC_WAIT: usize = 8;

/// Frist für einen Flow-Control-Rahmen, in Millisekunden.
///
/// `send` kennt keine Frist vom Aufrufer: das Steuergerät muss innerhalb von
/// `N_Bs` (1000 ms) antworten, sonst gilt der Block als verloren.
pub const FC_TIMEOUT_MS: u32 = 1_000;

/// Rechnet den STmin-Code eines Flow-Control-Rahmens in Millisekunden um.
///
/// `0x00..=0x7F` sind Millisekunden, `0xF1..=0xF9` Mikrosekunden. Alles
/// dazwischen ist reserviert und wird nach ISO 15765-2 wie `0x7F` (127 ms)
/// behandelt — lieber zu langsam als Rahmen verlieren. Mikrosekunden werden
/// aufgerundet, weil `sleep` feiner nicht kann.
pub fn stmin_ms(code: u8) -> u32 {
    match code {
        0x00..=0x7F => code as u32,
        0xF1..=0xF9 => 1,
        _ => 0x7F,
    }
}

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

        // Flow Control des Steuergeräts: Blockgröße und Mindestabstand gelten
        // für alle folgenden Consecutive Frames. Sie werden hier wirklich
        // eingehalten — ein Steuergerät, das BS>0 oder STmin>0 verlangt,
        // verwirft sonst Rahmen, und lange Nutzlasten (SFD-Token) brechen mit
        // N_Cr-Zeitüberschreitung ab.
        let fc = self.warte_flow_control(t, FC_TIMEOUT_MS)?;
        let mut blockgroesse = fc.data[1] as usize;
        let mut stmin = stmin_ms(fc.data[2]);

        let mut off = 6;
        let mut seq: u8 = 1;
        let mut im_block = 0usize;
        while off < payload.len() {
            let chunk = (payload.len() - off).min(7);
            let mut cf = [PAD; 8];
            cf[0] = 0x20 | (seq & 0x0F);
            cf[1..1 + chunk].copy_from_slice(&payload[off..off + chunk]);
            t.send(&CanFrame::new(self.req_id, &cf))?;
            off += chunk;
            seq = (seq + 1) & 0x0F;
            im_block += 1;

            // Kein Abstand nach dem letzten Rahmen: danach wartet der Tester
            // auf die Antwort, nicht auf das Steuergerät.
            if stmin > 0 && off < payload.len() {
                thread::sleep(Duration::from_millis(stmin as u64));
            }

            // Block voll und noch etwas zu senden: neues Flow Control anfordern.
            if blockgroesse > 0 && im_block >= blockgroesse && off < payload.len() {
                let fc = self.warte_flow_control(t, FC_TIMEOUT_MS)?;
                blockgroesse = fc.data[1] as usize;
                stmin = stmin_ms(fc.data[2]);
                im_block = 0;
            }
        }
        Ok(())
    }

    /// Wartet auf ein Flow-Control, das „weiter senden" erlaubt.
    ///
    /// `0x31` (wait) ist kein Fehler, sondern eine Vertröstung und wird bis zu
    /// [`MAX_FC_WAIT`]-mal hingenommen. `0x32` (overflow) heißt: das
    /// Steuergerät kann die Nachricht nicht aufnehmen — weiterzusenden wäre
    /// sinnlos.
    fn warte_flow_control<T: Transport>(&self, t: &mut T, timeout_ms: u32) -> Result<CanFrame> {
        for _ in 0..MAX_FC_WAIT {
            let f = t.recv(timeout_ms)?;
            if f.dlc < 3 {
                return Err(TransportError::Protocol("Flow-Control zu kurz"));
            }
            if f.data[0] & 0xF0 != 0x30 {
                return Err(TransportError::Protocol("kein Flow-Control-Frame"));
            }
            // Der Status steht in den unteren vier Bit: 0 weiter, 1 warten,
            // 2 Überlauf. Auf die oberen vier zu prüfen würde 0x31 und 0x32
            // als „weiter" durchgehen lassen — genau der Fehler, der lange
            // Nutzlasten am Fahrzeug scheitern lässt.
            match f.data[0] & 0x0F {
                0x00 => return Ok(f),
                0x01 => continue,
                0x02 => {
                    return Err(TransportError::Protocol(
                        "Steuergerät meldet Pufferüberlauf (FC 0x32)",
                    ))
                }
                _ => return Err(TransportError::Protocol("unbekannter Flow-Control-Status")),
            }
        }
        Err(TransportError::Protocol(
            "Steuergerät vertröstet zu oft (FC 0x31)",
        ))
    }

    /// Empfängt eine vollständige Nachricht und setzt sie zusammen.
    pub fn recv<T: Transport>(&self, t: &mut T, timeout_ms: u32) -> Result<Vec<u8>> {
        let f = t.recv(timeout_ms)?;
        // `dlc` kommt vom Gerät (über die FFI sogar aus der Plattform): ein
        // Rahmen mit 0 Byte oder mit einer Länge über 8 darf hier nicht zum
        // Indexfehler führen — `payload()` begrenzt auf die belegten Bytes,
        // und die Prüfungen unten rechnen damit, nicht mit `data`.
        let nutz = f.payload();
        if nutz.is_empty() {
            return Err(TransportError::Protocol("leerer Rahmen"));
        }
        match nutz[0] & 0xF0 {
            0x00 => {
                let n = (nutz[0] & 0x0F) as usize;
                if 1 + n > nutz.len() {
                    return Err(TransportError::Protocol("Single Frame länger als Rahmen"));
                }
                Ok(nutz[1..1 + n].to_vec())
            }
            0x10 => {
                if nutz.len() < 2 {
                    return Err(TransportError::Protocol("First Frame ohne Längenfeld"));
                }
                let total = (((nutz[0] & 0x0F) as usize) << 8) | nutz[1] as usize;
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
                    let cf_nutz = cf.payload();
                    if cf_nutz.is_empty() || cf_nutz[0] & 0xF0 != 0x20 {
                        return Err(TransportError::Protocol("erwarte Consecutive Frame"));
                    }
                    if cf_nutz[0] & 0x0F != expect_seq & 0x0F {
                        return Err(TransportError::Protocol("ISO-TP Sequenzfehler"));
                    }
                    let chunk = (total - got).min(7);
                    // Ein zu kurzer Rahmen würde sonst still mit Padding
                    // aufgefüllt — die Antwort wäre falsch statt gemeldet.
                    if cf_nutz.len() < 1 + chunk {
                        return Err(TransportError::Protocol("Consecutive Frame zu kurz"));
                    }
                    out[got..got + chunk].copy_from_slice(&cf_nutz[1..1 + chunk]);
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

    fn fc(bs: u8, stmin: u8) -> CanFrame {
        CanFrame::new(RSP_ID, &[0x30, bs, stmin])
    }

    #[test]
    fn pufferueberlauf_des_steuergeraets_bricht_ab() {
        let mut t = Loopback::new(vec![CanFrame::new(RSP_ID, &[0x32, 0, 0])]);
        let err = IsotpChannel::new().send(&mut t, &[0u8; 20]).unwrap_err();
        assert_eq!(
            err,
            TransportError::Protocol("Steuergerät meldet Pufferüberlauf (FC 0x32)")
        );
    }

    #[test]
    fn vertroestung_wird_hingenommen() {
        // Erst „wait", dann „continue to send" — der Sender darf nicht aufgeben.
        let mut t = Loopback::new(vec![CanFrame::new(RSP_ID, &[0x31, 0x00, 0x00]), fc(0, 0)]);
        IsotpChannel::new().send(&mut t, &[0u8; 20]).unwrap();
        assert_eq!(t.sent().len(), 1 + 2, "First Frame und zwei CF");
    }

    #[test]
    fn zu_viele_vertroestungen_brechen_ab() {
        let mut frames: Vec<CanFrame> = (0..MAX_FC_WAIT)
            .map(|_| CanFrame::new(RSP_ID, &[0x31, 0x00, 0x00]))
            .collect();
        frames.push(fc(0, 0));
        let mut t = Loopback::new(frames);
        let err = IsotpChannel::new().send(&mut t, &[0u8; 20]).unwrap_err();
        assert_eq!(
            err,
            TransportError::Protocol("Steuergerät vertröstet zu oft (FC 0x31)")
        );
    }

    #[test]
    fn blockgroesse_erzwingt_neues_flow_control() {
        // 20 Byte: FF + 2 CF. BS=1 heißt: nach dem ersten CF neu verhandeln.
        let mut t = Loopback::new(vec![fc(1, 0), fc(0, 0)]);
        IsotpChannel::new().send(&mut t, &[0xAA; 20]).unwrap();
        // 1 FF + 2 CF gesendet, 3 Flow-Control verbraucht.
        assert_eq!(t.sent().len(), 3);
        let err = t.recv(1).unwrap_err();
        assert_eq!(err, TransportError::Timeout, "kein FC mehr übrig");
    }

    #[test]
    fn stmin_wird_eingehalten() {
        // 20 Byte = FF + 2 CF, STmin 20 ms → eine Pause, mindestens 20 ms.
        let mut t = Loopback::new(vec![fc(0, 20)]);
        let start = std::time::Instant::now();
        IsotpChannel::new().send(&mut t, &[0x11; 20]).unwrap();
        assert!(
            start.elapsed() >= Duration::from_millis(20),
            "STmin nicht eingehalten: {:?}",
            start.elapsed()
        );
    }

    #[test]
    fn stmin_mit_mikrosekunden_wird_aufgerundet() {
        assert_eq!(stmin_ms(0x00), 0);
        assert_eq!(stmin_ms(0x7F), 127);
        // 0xF1..0xF9 sind 100..900 µs; alles unter einer Millisekunde wird
        // aufgerundet, weil `sleep` feiner nicht kann.
        assert_eq!(stmin_ms(0xF1), 1);
        assert_eq!(stmin_ms(0xF9), 1);
        // Reservierte Werte: nach ISO 15765-2 wie 0x7F behandeln.
        assert_eq!(stmin_ms(0x80), 127);
        assert_eq!(stmin_ms(0xFF), 127);
    }

    // --- Fremde Rahmen, die nicht passen (DoD 5) -------------------------

    #[test]
    fn leerer_rahmen_wird_abgelehnt() {
        let mut t = Loopback::new(vec![CanFrame::new(RSP_ID, &[])]);
        assert_eq!(
            IsotpChannel::new().recv(&mut t, 10).unwrap_err(),
            TransportError::Protocol("leerer Rahmen")
        );
    }

    #[test]
    fn single_frame_laenger_als_der_rahmen_wird_abgelehnt() {
        // Kündigt 15 Byte an, liefert aber vier.
        let mut t = Loopback::new(vec![CanFrame::new(RSP_ID, &[0x0F, 0x62, 0xF1, 0x90])]);
        assert_eq!(
            IsotpChannel::new().recv(&mut t, 10).unwrap_err(),
            TransportError::Protocol("Single Frame länger als Rahmen")
        );
    }

    #[test]
    fn first_frame_ohne_laengenfeld_wird_abgelehnt() {
        let mut t = Loopback::new(vec![CanFrame::new(RSP_ID, &[0x10])]);
        assert_eq!(
            IsotpChannel::new().recv(&mut t, 10).unwrap_err(),
            TransportError::Protocol("First Frame ohne Längenfeld")
        );
    }

    #[test]
    fn zu_kurzer_consecutive_frame_wird_abgelehnt() {
        // First Frame kündigt 10 Byte an, der Consecutive Frame trägt nur das
        // Sequenzbyte — der Rest wäre Padding und die Antwort falsch.
        let mut t = Loopback::new(vec![
            CanFrame::new(RSP_ID, &[0x10, 0x0A, 0x62, 0xF1, 0x90, 0x01, 0x02, 0x03]),
            CanFrame::new(RSP_ID, &[0x21]),
        ]);
        assert_eq!(
            IsotpChannel::new().recv(&mut t, 10).unwrap_err(),
            TransportError::Protocol("Consecutive Frame zu kurz")
        );
    }

    #[test]
    fn dlc_ueber_acht_wird_auf_acht_begrenzt() {
        // Über die FFI kann eine Plattform `dlc` falsch melden; das darf nicht
        // in einen Indexfehler laufen.
        let data = [0x02u8, 0x50, 0x03, PAD, PAD, PAD, PAD, PAD];
        let mut t = Loopback::new(vec![CanFrame {
            id: RSP_ID,
            data,
            dlc: 12,
        }]);
        assert_eq!(
            IsotpChannel::new().recv(&mut t, 10).unwrap(),
            vec![0x50, 0x03]
        );
    }

    #[test]
    fn sfd_token_mit_324_byte_geht_vollstaendig_raus() {
        // So lang ist ein SFD-Token samt Kopf — der Fall, für den es BS/STmin gibt.
        let payload: Vec<u8> = (0..324u32).map(|i| (i & 0xFF) as u8).collect();
        let mut t = Loopback::new(vec![fc(0, 0)]);
        IsotpChannel::new().send(&mut t, &payload).unwrap();

        let sent = t.sent();
        // 1 FF + ceil((324-6)/7) = 1 + 46
        assert_eq!(sent.len(), 47);
        let mut zusammen = Vec::new();
        zusammen.extend_from_slice(&sent[0].data[2..8]);
        for f in &sent[1..] {
            let n = (payload.len() - zusammen.len()).min(7);
            zusammen.extend_from_slice(&f.data[1..1 + n]);
        }
        assert_eq!(zusammen, payload);
    }
}
