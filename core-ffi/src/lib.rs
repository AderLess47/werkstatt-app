//! `vci-ffi` — die Schnittstelle, die Android und iOS sehen.
//!
//! Die IDL ist der annotierte Rust-Code unten (UniFFI-Proc-Macros, der
//! empfohlene Weg seit 0.24): eine Quelle, keine zweite Datei, die abdriften
//! kann. `uniffi-bindgen` erzeugt daraus Kotlin und Swift.
//!
//! Leitgedanke: der Plattform-Code (BLE, USB-Host) bleibt **auf der jeweiligen
//! Plattform**, weil dort die APIs liegen. Der Kern bringt nur die
//! Protokolllogik mit und bekommt den Transport als Callback hereingereicht.

use std::cell::RefCell;
use std::fmt;
use std::sync::{Arc, Mutex};

use vci_core::transport::{Capabilities, PAD};
use vci_core::{CanFrame, IsotpChannel, Loopback, Transport, TransportError};

uniffi::setup_scaffolding!();

/// Ein CAN-Rahmen, wie ihn die Plattform schickt oder empfängt.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CanFrameFfi {
    /// 11- oder 29-Bit-Kennung, z. B. `0x7E0`.
    pub id: u32,
    /// Höchstens acht Bytes; kürzere Listen werden beim Bauen aufgefüllt.
    pub data: Vec<u8>,
    /// Belegte Bytes (0–8).
    pub dlc: u8,
}

impl CanFrameFfi {
    fn to_core(&self) -> CanFrame {
        CanFrame::new(self.id, &self.data)
    }

    fn from_core(f: &CanFrame) -> Self {
        CanFrameFfi {
            id: f.id,
            data: f.data.to_vec(),
            dlc: f.dlc,
        }
    }
}

/// Fehler, die über die Sprachgrenze gehen.
///
/// Kotlin bekommt daraus eine Exception, Swift ein `throws`-Enum.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum VciError {
    /// Nichts empfangen innerhalb der Frist.
    Timeout,
    /// Transportfehler (Kabel, Pipe-Stall).
    Io,
    /// Gerät ist verschwunden; neu öffnen.
    Disconnected,
    /// Protokollfehler mit Klartext.
    ///
    /// Das Feld heißt `detail` und nicht `message`: UniFFI erzeugt daraus in
    /// Kotlin eine Property, und `message` verdeckt `Throwable.message` —
    /// die generierte Datei kompiliert dann nicht.
    Protocol { detail: String },
}

impl fmt::Display for VciError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            VciError::Timeout => write!(f, "Zeitüberschreitung"),
            VciError::Io => write!(f, "Transportfehler"),
            VciError::Disconnected => write!(f, "Gerät getrennt"),
            VciError::Protocol { detail } => write!(f, "Protokollfehler: {detail}"),
        }
    }
}

impl std::error::Error for VciError {}

impl From<TransportError> for VciError {
    fn from(e: TransportError) -> Self {
        match e {
            TransportError::Timeout => VciError::Timeout,
            TransportError::Io => VciError::Io,
            TransportError::Disconnected => VciError::Disconnected,
            TransportError::Protocol(detail) => VciError::Protocol {
                detail: detail.to_string(),
            },
        }
    }
}

/// Der Transport, den die Plattform bereitstellt.
///
/// In Kotlin implementiert man dieses Interface mit dem BLE- oder USB-Stack,
/// in Swift mit CoreBluetooth bzw. dem ExternalAccessory-Framework.
#[uniffi::export(callback_interface)]
pub trait CanTransport: Send + Sync {
    fn send(&self, frame: CanFrameFfi) -> Result<(), VciError>;

    /// `None` bedeutet: innerhalb der Frist ist nichts gekommen.
    fn recv(&self, timeout_ms: u32) -> Result<Option<CanFrameFfi>, VciError>;

    /// Bordspannung in mV, sofern die Plattform sie messen kann.
    fn vbat_mv(&self) -> Result<Option<u32>, VciError>;
}

/// Adapter: der Kern will `&mut self`, der Callback ist `&self`.
///
/// Der Kern kennt nur seine eigenen Fehlerarten. Damit ein Fehler aus Kotlin
/// oder Swift nicht zu „Transportfehler" verflacht, wird er hier gemerkt und
/// nach dem Aufruf unverändert zurückgegeben.
struct ForeignTransport<'a> {
    inner: &'a dyn CanTransport,
    last: RefCell<Option<VciError>>,
}

impl<'a> ForeignTransport<'a> {
    fn new(inner: &'a dyn CanTransport) -> Self {
        ForeignTransport {
            inner,
            last: RefCell::new(None),
        }
    }

    /// Nimmt den ursprünglichen Plattform-Fehler zurück, falls einer anfiel.
    fn finish<T>(&self, result: vci_core::Result<T>) -> Result<T, VciError> {
        match result {
            Ok(v) => Ok(v),
            Err(e) => match self.last.borrow_mut().take() {
                Some(original) => Err(original),
                None => Err(VciError::from(e)),
            },
        }
    }
}

impl Transport for ForeignTransport<'_> {
    fn send(&mut self, frame: &CanFrame) -> vci_core::Result<()> {
        match self.inner.send(CanFrameFfi::from_core(frame)) {
            Ok(()) => Ok(()),
            Err(e) => {
                *self.last.borrow_mut() = Some(e);
                Err(TransportError::Io)
            }
        }
    }

    fn recv(&mut self, timeout_ms: u32) -> vci_core::Result<CanFrame> {
        match self.inner.recv(timeout_ms) {
            Ok(Some(f)) => Ok(f.to_core()),
            Ok(None) => Err(TransportError::Timeout),
            Err(e) => {
                *self.last.borrow_mut() = Some(e);
                Err(TransportError::Io)
            }
        }
    }

    fn vbat_mv(&mut self) -> vci_core::Result<u32> {
        match self.inner.vbat_mv() {
            Ok(Some(mv)) => Ok(mv),
            Ok(None) => Err(TransportError::Protocol("Bordspannung nicht messbar")),
            Err(e) => {
                *self.last.borrow_mut() = Some(e);
                Err(TransportError::Io)
            }
        }
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }
}

/// ISO-TP-Kanal für die App.
#[derive(uniffi::Object)]
pub struct IsoTp {
    inner: IsotpChannel,
}

#[uniffi::export]
impl IsoTp {
    /// Standardadressierung: Anfragen `0x7E0`, Antworten `0x7E8`.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(IsoTp {
            inner: IsotpChannel::new(),
        })
    }

    /// Für Fahrzeuge mit abweichender Adressierung.
    #[uniffi::constructor]
    pub fn with_ids(req_id: u32, rsp_id: u32) -> Arc<Self> {
        Arc::new(IsoTp {
            inner: IsotpChannel::with_ids(req_id, rsp_id),
        })
    }

    /// Zerlegt die Nutzlast und sendet sie über den Transport.
    pub fn send(&self, transport: Box<dyn CanTransport>, payload: Vec<u8>) -> Result<(), VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.send(&mut t, &payload);
        t.finish(res)
    }

    /// Empfängt eine vollständige Nachricht und setzt sie zusammen.
    pub fn recv(
        &self,
        transport: Box<dyn CanTransport>,
        timeout_ms: u32,
    ) -> Result<Vec<u8>, VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.recv(&mut t, timeout_ms);
        t.finish(res)
    }

    pub fn req_id(&self) -> u32 {
        self.inner.req_id()
    }

    pub fn rsp_id(&self) -> u32 {
        self.inner.rsp_id()
    }
}

/// Transport im Speicher — für Demos und für Tests auf der Plattform, bevor
/// echte Hardware angebunden ist.
#[derive(uniffi::Object)]
pub struct LoopbackTransport {
    inner: Mutex<Loopback>,
}

#[uniffi::export]
impl LoopbackTransport {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(LoopbackTransport {
            inner: Mutex::new(Loopback::empty()),
        })
    }

    /// Reiht einen Rahmen ein, den der Kern empfangen soll.
    pub fn push(&self, frame: CanFrameFfi) {
        if let Ok(mut l) = self.inner.lock() {
            l.push(frame.to_core());
        }
    }

    /// Was der Kern bisher gesendet hat (für Anzeige und Tests).
    pub fn sent(&self) -> Vec<CanFrameFfi> {
        match self.inner.lock() {
            Ok(l) => l.sent().iter().map(CanFrameFfi::from_core).collect(),
            Err(_) => Vec::new(),
        }
    }

    pub fn clear_sent(&self) {
        if let Ok(mut l) = self.inner.lock() {
            l.clear_sent();
        }
    }
}

impl CanTransport for LoopbackTransport {
    fn send(&self, frame: CanFrameFfi) -> Result<(), VciError> {
        let mut l = self
            .inner
            .lock()
            .map_err(|_| VciError::Io)?;
        l.send(&frame.to_core()).map_err(VciError::from)
    }

    fn recv(&self, timeout_ms: u32) -> Result<Option<CanFrameFfi>, VciError> {
        let mut l = self.inner.lock().map_err(|_| VciError::Io)?;
        match l.recv(timeout_ms) {
            Ok(f) => Ok(Some(CanFrameFfi::from_core(&f))),
            Err(TransportError::Timeout) => Ok(None),
            Err(e) => Err(VciError::from(e)),
        }
    }

    fn vbat_mv(&self) -> Result<Option<u32>, VciError> {
        Ok(None) // der Loopback kennt keine Spannung
    }
}

/// Damit der Loopback auch als `Box<dyn CanTransport>` übergeben werden kann,
/// ohne ihn auszupacken (bequem in Tests und Demos).
impl CanTransport for Arc<LoopbackTransport> {
    fn send(&self, frame: CanFrameFfi) -> Result<(), VciError> {
        CanTransport::send(self.as_ref(), frame)
    }

    fn recv(&self, timeout_ms: u32) -> Result<Option<CanFrameFfi>, VciError> {
        CanTransport::recv(self.as_ref(), timeout_ms)
    }

    fn vbat_mv(&self) -> Result<Option<u32>, VciError> {
        CanTransport::vbat_mv(self.as_ref())
    }
}

/// Padding-Wert des Kerns, damit die Plattform Rahmen gleich auffüllt.
pub fn padding_byte() -> u8 {
    PAD
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_ueber_den_callback() {
        let lb = LoopbackTransport::new();
        // Antwort des Steuergeräts einreihen: Single Frame 02 7E 00.
        lb.push(CanFrameFfi {
            id: 0x7E8,
            data: vec![0x02, 0x7E, 0x00],
            dlc: 8,
        });

        let iso = IsoTp::new();
        let payload = vec![0x3E, 0x00];
        iso.send(Box::new(lb.clone()), payload.clone()).unwrap();

        // Der Kern hat die Anfrage als Single Frame gesendet.
        let sent = lb.sent();
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].id, 0x7E0);
        assert_eq!(&sent[0].data[..3], &[0x02, 0x3E, 0x00]);

        let reply = iso.recv(Box::new(lb.clone()), 100).unwrap();
        assert_eq!(reply, vec![0x7E, 0x00]);
    }

    #[test]
    fn zeitueberschreitung_kommt_als_fehler_an() {
        let lb = LoopbackTransport::new();
        let iso = IsoTp::new();
        let err = iso.recv(Box::new(lb), 10).unwrap_err();
        assert_eq!(err, VciError::Timeout);
    }
}
