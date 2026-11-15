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

use vci_core::sfd::{SfdError, SfdFehler};
use vci_core::transport::{Capabilities, PAD};
use vci_core::{CanFrame, IsotpChannel, Loopback, SfdClient, Transport, TransportError};

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
    /// Negative Antwort des Steuergeräts (UDS NRC, ISO 14229-1).
    ///
    /// Eigener Fall statt Klartext, damit die App auf `0x78` (Antwort kommt
    /// noch), `0x35` (falscher Token) oder `0x36` (zu viele Versuche)
    /// unterschiedlich reagieren kann. Die Felder sind `u32`, weil UniFFI
    /// `u8` in Kotlin als `UByte` abbildet — für einen Vergleich mit
    /// Konstanten ist das nur Zusatzarbeit.
    Nrc { dienst: u32, code: u32 },
    /// SFD-Meldung, deren Form nicht zum Ablauf passt (zu kurz, falscher
    /// Dienst, ungültiger Token-Text).
    Sfd { detail: String },
}

impl fmt::Display for VciError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            VciError::Timeout => write!(f, "Zeitüberschreitung"),
            VciError::Io => write!(f, "Transportfehler"),
            VciError::Disconnected => write!(f, "Gerät getrennt"),
            VciError::Protocol { detail } => write!(f, "Protokollfehler: {detail}"),
            VciError::Nrc { dienst, code } => write!(
                f,
                "abgelehnt (Dienst 0x{dienst:02X}, NRC 0x{code:02X}: {})",
                vci_core::sfd::nrc_text(*code as u8)
            ),
            VciError::Sfd { detail } => write!(f, "SFD: {detail}"),
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

impl From<SfdError> for VciError {
    fn from(e: SfdError) -> Self {
        match e {
            SfdError::Nrc { dienst, code } => VciError::Nrc {
                dienst: dienst as u32,
                code: code as u32,
            },
            other => VciError::Sfd {
                detail: other.to_string(),
            },
        }
    }
}

impl From<SfdFehler> for VciError {
    fn from(e: SfdFehler) -> Self {
        match e {
            SfdFehler::Transport(t) => VciError::from(t),
            SfdFehler::Sfd(s) => VciError::from(s),
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

    /// Dasselbe für SFD-Abläufe, deren Fehlerart `SfdFehler` ist.
    fn finish_sfd<T>(&self, result: std::result::Result<T, SfdFehler>) -> Result<T, VciError> {
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

/// Roher SFD-Status, wie ihn das Steuergerät liefert.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SfdStatusFfi {
    /// Bytes hinter `62 01 74`.
    pub daten: Vec<u8>,
    /// Dieselben Bytes als Hex — für die Anzeige.
    pub hex: String,
}

/// SFD-Ablauf für die App („Schutz Fahrzeug Diagnose", VAG).
///
/// Der Token wird **nicht** hier erzeugt: er kommt aus dem Herstellerportal
/// und wird als Hex-Text übergeben. Dieses Objekt baut die UDS-Nachrichten,
/// schickt sie über den übergebenen Transport und wertet die Antwort aus —
/// inklusive NRC (`VciError::Nrc`) und der Aufschübe (`NRC 0x78`).
#[derive(uniffi::Object)]
pub struct Sfd {
    inner: SfdClient,
}

#[uniffi::export]
impl Sfd {
    /// Standardadressierung: Anfragen `0x7E0`, Antworten `0x7E8`.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Sfd {
            inner: SfdClient::new(),
        })
    }

    /// Für Fahrzeuge mit abweichender Adressierung.
    #[uniffi::constructor]
    pub fn with_ids(req_id: u32, rsp_id: u32) -> Arc<Self> {
        Arc::new(Sfd {
            inner: SfdClient::with_ids(req_id, rsp_id),
        })
    }

    /// `22 01 74` — Status des SFD-Schutzes lesen.
    pub fn status(
        &self,
        transport: Box<dyn CanTransport>,
        timeout_ms: u32,
    ) -> Result<SfdStatusFfi, VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.status(&mut t, timeout_ms);
        t.finish_sfd(res).map(|s| SfdStatusFfi {
            hex: vci_core::sfd::hex(&s.daten),
            daten: s.daten,
        })
    }

    /// `31 01 C0 04 <Rolle>` — Challenge anfordern, Rückgabe als Hex-Text.
    pub fn challenge(
        &self,
        transport: Box<dyn CanTransport>,
        rolle: u32,
        timeout_ms: u32,
    ) -> Result<String, VciError> {
        let rolle = u8::try_from(rolle).map_err(|_| VciError::Sfd {
            detail: format!("Rolle {rolle} passt nicht in ein Byte"),
        })?;
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.challenge(&mut t, rolle, timeout_ms);
        t.finish_sfd(res).map(|c| vci_core::sfd::hex(&c))
    }

    /// `31 01 C0 08 <Token>` — Token als Hex-Text senden (entsperren).
    ///
    /// Der Text darf Leerzeichen, `:` und `-` als Trenner enthalten, so wie
    /// Portale Token ausgeben. Ungültige Zeichen werden abgelehnt, nicht
    /// übersprungen.
    pub fn entsperren(
        &self,
        transport: Box<dyn CanTransport>,
        token_hex: String,
        timeout_ms: u32,
    ) -> Result<(), VciError> {
        let token = vci_core::sfd::aus_hex(&token_hex).map_err(VciError::from)?;
        self.entsperren_mit_bytes(transport, token, timeout_ms)
    }

    /// Dasselbe mit einem schon vorhandenen Byte-Array.
    pub fn entsperren_mit_bytes(
        &self,
        transport: Box<dyn CanTransport>,
        token: Vec<u8>,
        timeout_ms: u32,
    ) -> Result<(), VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.entsperren(&mut t, &token, timeout_ms);
        t.finish_sfd(res)
    }

    /// `31 01 C0 09` — wieder sperren.
    pub fn sperren(
        &self,
        transport: Box<dyn CanTransport>,
        timeout_ms: u32,
    ) -> Result<(), VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.sperren(&mut t, timeout_ms);
        t.finish_sfd(res)
    }
}

/// Wandelt Hex-Text in Bytes — dieselbe Regel wie beim Token.
#[uniffi::export]
pub fn sfd_aus_hex(text: String) -> Result<Vec<u8>, VciError> {
    vci_core::sfd::aus_hex(&text).map_err(VciError::from)
}

/// Wandelt Bytes in Hex-Text (Großbuchstaben, ohne Trenner).
#[uniffi::export]
pub fn sfd_hex(daten: Vec<u8>) -> String {
    vci_core::sfd::hex(&daten)
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

    fn push_sf(lb: &Arc<LoopbackTransport>, uds: &[u8]) {
        let mut data = vec![uds.len() as u8];
        data.extend_from_slice(uds);
        lb.push(CanFrameFfi {
            id: 0x7E8,
            data,
            dlc: 8,
        });
    }

    #[test]
    fn sfd_status_und_challenge_ueber_die_grenze() {
        let lb = LoopbackTransport::new();
        push_sf(&lb, &[0x62, 0x01, 0x74, 0x01]);
        let sfd = Sfd::new();

        let status = sfd.status(Box::new(lb.clone()), 100).unwrap();
        assert_eq!(status.daten, vec![0x01]);
        assert_eq!(status.hex, "01");
        assert_eq!(lb.sent()[0].data[..4], [0x03, 0x22, 0x01, 0x74]);

        lb.clear_sent();
        push_sf(&lb, &[0x71, 0x01, 0xC0, 0x04, 0xA1, 0xB2]);
        let challenge = sfd.challenge(Box::new(lb.clone()), 0x01, 100).unwrap();
        assert_eq!(challenge, "A1B2");
        assert_eq!(lb.sent()[0].data[..5], [0x05, 0x31, 0x01, 0xC0, 0x04]);
    }

    #[test]
    fn sfd_nrc_kommt_als_eigener_fehlerfall() {
        let lb = LoopbackTransport::new();
        push_sf(&lb, &[0x7F, 0x31, 0x35]);
        let err = Sfd::new()
            .entsperren(Box::new(lb), "DEAD".to_string(), 100)
            .unwrap_err();
        assert_eq!(
            err,
            VciError::Nrc {
                dienst: 0x31,
                code: 0x35
            }
        );
        assert!(err.to_string().contains("invalidKey"), "{err}");
    }

    #[test]
    fn sfd_lehnt_ungueltigen_hex_text_ab() {
        let lb = LoopbackTransport::new();
        let err = Sfd::new()
            .entsperren(Box::new(lb), "A1G2".to_string(), 100)
            .unwrap_err();
        assert!(matches!(err, VciError::Sfd { .. }), "{err}");
    }

    #[test]
    fn sfd_token_mit_300_byte_geht_ueber_die_grenze() {
        let lb = LoopbackTransport::new();
        // Flow Control, danach die positive Antwort — der Sender wartet selbst.
        lb.push(CanFrameFfi {
            id: 0x7E8,
            data: vec![0x30, 0x00, 0x00],
            dlc: 8,
        });
        push_sf(&lb, &[0x71, 0x01, 0xC0, 0x08]);

        let token = vec![0x5Au8; 300];
        Sfd::new()
            .entsperren_mit_bytes(Box::new(lb.clone()), token.clone(), 100)
            .unwrap();

        let sent = lb.sent();
        assert_eq!(sent[0].data[0] & 0xF0, 0x10, "First Frame");
        let angekuendigt = (((sent[0].data[0] as usize) & 0x0F) << 8) | sent[0].data[1] as usize;
        assert_eq!(angekuendigt, 4 + token.len());
        assert!(sent.len() > 40, "300 Byte brauchen viele Rahmen");
    }
}
