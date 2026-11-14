//! Transport-Abstraktion — die einzige Stelle im Kern, die Hardware kennt.
//!
//! Alles darüber (ISO-TP, UDS, Funktionen) ist plattformunabhängig und läuft in
//! Tests gegen [`Loopback`](crate::loopback::Loopback) oder gegen den
//! Konformitätskorpus aus der C-Implementierung.
//!
//! Ein Transport ist im Fehlerfall immer noch benutzbar oder klar getrennt:
//! `Timeout` heißt „nichts gehört", `Disconnected` heißt „Gerät ist weg".

use std::fmt;

/// Füllwert für nicht belegte Bytes im Rahmen.
pub const PAD: u8 = 0xAA;

/// Ein CAN-Rahmen mit bis zu acht Nutzbytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CanFrame {
    pub id: u32,
    pub data: [u8; 8],
    /// Belegte Bytes; der Rest ist Padding.
    pub dlc: u8,
}

impl CanFrame {
    /// Baut einen Rahmen und füllt den Rest mit [`PAD`].
    ///
    /// Mehr als acht Bytes werden abgeschnitten — ein CAN-Rahmen kann nicht
    /// mehr tragen, und stilles Verlängern wäre schlimmer als Kürzen.
    pub fn new(id: u32, payload: &[u8]) -> Self {
        let mut data = [PAD; 8];
        let n = payload.len().min(8);
        data[..n].copy_from_slice(&payload[..n]);
        CanFrame {
            id,
            data,
            dlc: n as u8,
        }
    }

    /// Die belegten Bytes ohne Padding.
    pub fn payload(&self) -> &[u8] {
        &self.data[..(self.dlc as usize).min(8)]
    }
}

/// Fehler eines Transports.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportError {
    /// Innerhalb der Frist ist nichts gekommen.
    Timeout,
    /// Transportfehler (Kabel, Pipe-Stall, abgebrochene Übertragung).
    Io,
    /// Gerät ist verschwunden; ein erneutes Öffnen ist nötig.
    Disconnected,
    /// Protokollfehler; die Meldung nennt die Stelle im Ablauf.
    Protocol(&'static str),
}

impl fmt::Display for TransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TransportError::Timeout => write!(f, "Zeitüberschreitung"),
            TransportError::Io => write!(f, "Transportfehler"),
            TransportError::Disconnected => write!(f, "Gerät getrennt"),
            TransportError::Protocol(msg) => write!(f, "Protokollfehler: {msg}"),
        }
    }
}

impl std::error::Error for TransportError {}

pub type Result<T> = std::result::Result<T, TransportError>;

/// Was ein Gerät kann. Die App entscheidet daran, welche Wege sie anbietet.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Capabilities {
    pub can_fd: bool,
    pub k_line: bool,
    pub doip: bool,
}

/// Ein CAN-Transport.
///
/// Implementierungen: BLE, USB (WinUSB/libusb), J2534-DLL auf dem Desktop und
/// [`Loopback`](crate::loopback::Loopback) für Tests.
pub trait Transport {
    fn send(&mut self, frame: &CanFrame) -> Result<()>;

    /// Wartet höchstens `timeout_ms` auf einen Rahmen.
    fn recv(&mut self, timeout_ms: u32) -> Result<CanFrame>;

    /// Bordspannung am OBD-Stecker in mV, sofern das Gerät sie messen kann.
    fn vbat_mv(&mut self) -> Result<u32> {
        Err(TransportError::Protocol("Bordspannung nicht messbar"))
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }
}
