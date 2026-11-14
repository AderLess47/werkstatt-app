//! Loopback-Transport für Tests ohne Hardware.
//!
//! Zwei Verwendungen: eine feste Liste von Rahmen abspielen (Antwort eines
//! Steuergeräts) und mitschreiben, was der Kern gesendet hat.

use std::collections::VecDeque;

use crate::transport::{CanFrame, Capabilities, Result, Transport, TransportError};

/// In-Memory-Transport.
pub struct Loopback {
    rx: VecDeque<CanFrame>,
    tx: Vec<CanFrame>,
    /// Simuliert ein Gerät, das die Bordspannung nicht messen kann.
    caps: Capabilities,
}

impl Loopback {
    /// Spielt die übergebenen Rahmen in dieser Reihenfolge ab.
    pub fn new(frames: Vec<CanFrame>) -> Self {
        Loopback {
            rx: frames.into(),
            tx: Vec::new(),
            caps: Capabilities::default(),
        }
    }

    /// Leerer Transport: jede Anfrage läuft in eine Zeitüberschreitung.
    pub fn empty() -> Self {
        Loopback::new(Vec::new())
    }

    /// Was der Kern bisher gesendet hat.
    pub fn sent(&self) -> &[CanFrame] {
        &self.tx
    }

    /// Weitere Rahmen einreihen (z. B. zwischen zwei Schritten).
    pub fn push(&mut self, frame: CanFrame) {
        self.rx.push_back(frame);
    }

    pub fn clear_sent(&mut self) {
        self.tx.clear();
    }

    pub fn with_capabilities(mut self, caps: Capabilities) -> Self {
        self.caps = caps;
        self
    }
}

impl Transport for Loopback {
    fn send(&mut self, frame: &CanFrame) -> Result<()> {
        self.tx.push(*frame);
        Ok(())
    }

    fn recv(&mut self, _timeout_ms: u32) -> Result<CanFrame> {
        self.rx.pop_front().ok_or(TransportError::Timeout)
    }

    fn capabilities(&self) -> Capabilities {
        self.caps
    }
}
