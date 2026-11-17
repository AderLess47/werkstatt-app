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

use vci_core::audit::{AuditFehler, AuditKette, Ergebnis};
use vci_core::content::InhaltsFehler;
use vci_core::function::{AusfuehrungsFehler, FunctionDef, Kontext};
use vci_core::sfd::{SfdError, SfdFehler};
use vci_core::transport::{Capabilities, PAD};
use vci_core::{CanFrame, IsotpChannel, Loopback, SfdClient, Transport, TransportError, UdsClient};

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

impl From<vci_core::uds::UdsFehler> for VciError {
    fn from(e: vci_core::uds::UdsFehler) -> Self {
        match e {
            vci_core::uds::UdsFehler::Nrc { dienst, code } => VciError::Nrc {
                dienst: dienst as u32,
                code: code as u32,
            },
            vci_core::uds::UdsFehler::Transport(t) => VciError::from(t),
            other => VciError::Protocol {
                detail: other.to_string(),
            },
        }
    }
}

impl From<InhaltsFehler> for VciError {
    fn from(e: InhaltsFehler) -> Self {
        VciError::Protocol {
            detail: e.to_string(),
        }
    }
}

impl From<AuditFehler> for VciError {
    fn from(e: AuditFehler) -> Self {
        VciError::Protocol {
            detail: e.to_string(),
        }
    }
}

impl From<AusfuehrungsFehler> for VciError {
    fn from(e: AusfuehrungsFehler) -> Self {
        match e {
            AusfuehrungsFehler::Transport(t) => VciError::from(t),
            AusfuehrungsFehler::Uds(u) => VciError::from(u),
            other => VciError::Protocol {
                detail: other.to_string(),
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

    /// Und für UDS-Abläufe (`UdsFehler`) — etwa den Sicherheitszugriff.
    fn finish_uds<T>(
        &self,
        result: std::result::Result<T, vci_core::uds::UdsFehler>,
    ) -> Result<T, VciError> {
        match result {
            Ok(v) => Ok(v),
            Err(e) => match self.last.borrow_mut().take() {
                Some(original) => Err(original),
                None => Err(VciError::from(e)),
            },
        }
    }

    /// Und für Funktionsabläufe (`AusfuehrungsFehler`).
    fn finish_funktion<T>(
        &self,
        result: std::result::Result<T, AusfuehrungsFehler>,
    ) -> Result<T, VciError> {
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
        let mut l = self.inner.lock().map_err(|_| VciError::Io)?;
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
    /// Die ganze Antwort als Hex, `62 01 74` eingeschlossen.
    ///
    /// SFD1 und SFD2 liefern unterschiedlich lange Antworten. Die App zeigt
    /// diese Zeichenkette immer, damit eine Deutung, die nicht passt, am
    /// Rohwert auffällt statt geglaubt zu werden.
    pub roh_hex: String,
}

/// SFD-Ablauf für die App („Schutz Fahrzeug Diagnose", VAG).
///
/// Der Token wird **nicht** hier erzeugt: er kommt aus dem Herstellerportal
/// und wird als Hex-Text übergeben. Dieses Objekt baut die UDS-Nachrichten,
/// schickt sie über den übergebenen Transport und wertet die Antwort aus —
/// inklusive NRC (`VciError::Nrc`) und der Aufschübe (`NRC 0x78`).
///
/// Entsperren und Sperren gehen **durch die Kette**: das Objekt bekommt sie
/// beim Bauen mit, nicht je Aufruf. Damit gibt es keinen Weg, an dem ein
/// Unlock vorbeigeht — ein Nachweis, der nur geführt wird, wenn jemand daran
/// denkt, ist keiner.
#[derive(uniffi::Object)]
pub struct Sfd {
    inner: SfdClient,
    audit: Arc<Audit>,
    vin: String,
}

/// Wanduhr in Millisekunden für Audit-Einträge.
///
/// Der Kern bekommt die Zeit **übergeben** und bleibt damit prüfbar; hier, an
/// der Grenze zur Plattform, gibt es eine Uhr. Vor 1970 liefert das 0 — kein
/// Grund, den Vorgang scheitern zu lassen.
fn jetzt_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[uniffi::export]
impl Sfd {
    /// Standardadressierung: Anfragen `0x7E0`, Antworten `0x7E8`.
    ///
    /// `vin` steht im Audit-Eintrag; kennt die App ihn nicht, gehört dort ein
    /// ehrliches „unbekannt“ hin statt einer erfundenen Nummer.
    #[uniffi::constructor]
    pub fn new(audit: Arc<Audit>, vin: String) -> Arc<Self> {
        Arc::new(Sfd {
            inner: SfdClient::new(),
            audit,
            vin,
        })
    }

    /// Für Fahrzeuge mit abweichender Adressierung.
    #[uniffi::constructor]
    pub fn with_ids(audit: Arc<Audit>, vin: String, req_id: u32, rsp_id: u32) -> Arc<Self> {
        Arc::new(Sfd {
            inner: SfdClient::with_ids(req_id, rsp_id),
            audit,
            vin,
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
            roh_hex: vci_core::sfd::hex(&s.roh),
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
        let mut kette = self.audit.inner.lock().map_err(|_| VciError::Io)?;
        let res = self.inner.entsperren_protokolliert(
            &mut t,
            &token,
            timeout_ms,
            &self.vin,
            &mut kette,
            jetzt_ms(),
        );
        // Sperre loslassen, bevor der Fehler nach außen geht: ein zweiter
        // Aufruf darf nicht an einer gehaltenen Sperre hängen.
        drop(kette);
        t.finish_sfd(res)
    }

    /// `10 03` — Extended Session starten.
    ///
    /// Vor Challenge und Token nötig, wenn das Steuergerät `31 01 C0 04` in der
    /// Default-Session mit NRC `0x7E`/`0x7F` ablehnt.
    pub fn extended_session(
        &self,
        transport: Box<dyn CanTransport>,
        timeout_ms: u32,
    ) -> Result<(), VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.extended(&mut t, timeout_ms);
        t.finish_sfd(res)
    }

    /// `10 <Sitzung>` — Sitzungswechsel mit beliebiger Subfunktion.
    pub fn sitzung(
        &self,
        transport: Box<dyn CanTransport>,
        sitzung: u32,
        timeout_ms: u32,
    ) -> Result<(), VciError> {
        let sitzung = u8::try_from(sitzung).map_err(|_| VciError::Sfd {
            detail: format!("Sitzung {sitzung} passt nicht in ein Byte"),
        })?;
        let mut t = ForeignTransport::new(transport.as_ref());
        let res = self.inner.sitzung(&mut t, sitzung, timeout_ms);
        t.finish_sfd(res)
    }

    /// `31 01 C0 09` — wieder sperren.
    pub fn sperren(
        &self,
        transport: Box<dyn CanTransport>,
        timeout_ms: u32,
    ) -> Result<(), VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let mut kette = self.audit.inner.lock().map_err(|_| VciError::Io)?;
        let res =
            self.inner
                .sperren_protokolliert(&mut t, timeout_ms, &self.vin, &mut kette, jetzt_ms());
        drop(kette);
        t.finish_sfd(res)
    }
}

/// Rechnet zu einem Seed den Schlüssel einer Ebene — auf der Plattform.
///
/// Der Kern bringt den **Ablauf** mit (`27 <Ebene>` fragen, `27 <Ebene+1>`
/// schicken, Bestätigung prüfen), aber keinen Algorithmus: die sind
/// herstellerspezifisch und lizenzpflichtig
/// ([docs/implementierungsplan.md](../../docs/implementierungsplan.md) §3). Die
/// App reicht hier ihren Anbieter herein; der Kern ruft ihn genau einmal je
/// Zugriff und sendet, was zurückkommt.
///
/// `ebene` ist `u32` wie überall an dieser Grenze (`u8` würde in Kotlin zu
/// `UByte`), `seed` kommt als Liste von Bytes an — so, wie er auf dem Bus lag.
#[uniffi::export(callback_interface)]
pub trait SchluesselAnbieter: Send + Sync {
    /// Schlüssel zu `seed` für `ebene`.
    ///
    /// Eine leere Antwort heißt „kein Schlüssel“: der Kern sendet dann nichts
    /// und meldet einen Protokollfehler, statt einen Schlüssel zu erfinden.
    fn schluessel(&self, ebene: u32, seed: Vec<u8>) -> Vec<u8>;
}

/// Vermittelt zwischen dem Kern-Trait und dem Callback der Plattform.
///
/// Der Kern kennt nur `SecurityProvider`; hier wird die Ebene auf `u8`
/// zurückgeschnitten (der Kern arbeitet byteweise) und der Seed kopiert, weil
/// er über die Sprachgrenze geht.
struct AnbieterAdapter<'a> {
    inner: &'a dyn SchluesselAnbieter,
}

impl vci_core::uds::SecurityProvider for AnbieterAdapter<'_> {
    fn schluessel(&self, ebene: u8, seed: &[u8]) -> Vec<u8> {
        self.inner.schluessel(ebene as u32, seed.to_vec())
    }
}

/// Ausgang eines Sicherheitszugriffs, wie ihn die App anzeigt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SicherheitFfi {
    /// Leerer Seed (oder nur Nullbytes): die Ebene war schon offen, es wurde
    /// **kein** Schlüssel gesendet.
    BereitsOffen,
    /// Seed und Schlüssel gesendet, Bestätigung erhalten.
    Entsperrt,
}

/// `27` (SecurityAccess) für die App.
///
/// Der Ablauf liegt im Kern (`UdsClient::security_access`); dieses Objekt
/// reicht Transport und Anbieter durch und übersetzt das Ergebnis. Es gibt
/// bewusst keinen Weg, bei dem der Kern selbst einen Schlüssel rechnet — der
/// Algorithmus kommt aus dem Anbieter der Plattform.
///
/// Wie [`Sfd`] geht der Vorgang **durch die Kette**: sie kommt beim Bauen mit,
/// nicht je Aufruf, und der Eintrag enthält Ebene und Ausgang — keinen Seed,
/// keinen Schlüssel.
#[derive(uniffi::Object)]
pub struct Sicherheit {
    inner: UdsClient,
    audit: Arc<Audit>,
    vin: String,
}

#[uniffi::export]
impl Sicherheit {
    /// Standardadressierung: Anfragen `0x7E0`, Antworten `0x7E8`.
    ///
    /// `vin` steht im Audit-Eintrag; kennt die App ihn nicht, gehört dort ein
    /// ehrliches „unbekannt“ hin statt einer erfundenen Nummer.
    #[uniffi::constructor]
    pub fn new(audit: Arc<Audit>, vin: String) -> Arc<Self> {
        Arc::new(Sicherheit {
            inner: UdsClient::new(),
            audit,
            vin,
        })
    }

    /// Für Fahrzeuge mit abweichender Adressierung.
    #[uniffi::constructor]
    pub fn with_ids(audit: Arc<Audit>, vin: String, req_id: u32, rsp_id: u32) -> Arc<Self> {
        Arc::new(Sicherheit {
            inner: UdsClient::with_ids(req_id, rsp_id),
            audit,
            vin,
        })
    }

    /// `27 <Ebene>` / `27 <Ebene+1> <Schlüssel>` — Ebene öffnen.
    ///
    /// Der Anbieter wird **nur** gefragt, wenn das Steuergerät einen Seed
    /// geliefert hat: bei einem leeren Seed war die Ebene schon offen, und ein
    /// Schlüssel dazu würde am Steuergerät als `0x35` landen.
    pub fn security_access(
        &self,
        transport: Box<dyn CanTransport>,
        ebene: u32,
        anbieter: Box<dyn SchluesselAnbieter>,
        timeout_ms: u32,
    ) -> Result<SicherheitFfi, VciError> {
        let ebene = u8::try_from(ebene).map_err(|_| VciError::Protocol {
            detail: format!("Ebene {ebene} passt nicht in ein Byte"),
        })?;
        let adapter = AnbieterAdapter {
            inner: anbieter.as_ref(),
        };
        let mut t = ForeignTransport::new(transport.as_ref());
        let mut kette = self.audit.inner.lock().map_err(|_| VciError::Io)?;
        let res = self
            .inner
            .security_access(&mut t, ebene, &adapter, timeout_ms);
        self.protokolliere(&mut kette, ebene, &res);
        // Sperre loslassen, bevor der Fehler nach außen geht: ein zweiter
        // Aufruf darf nicht an einer gehaltenen Sperre hängen.
        drop(kette);
        t.finish_uds(res).map(|z| match z {
            vci_core::uds::SecurityZugriff::BereitsOffen => SicherheitFfi::BereitsOffen,
            vci_core::uds::SecurityZugriff::Entsperrt => SicherheitFfi::Entsperrt,
        })
    }
}

impl Sicherheit {
    /// Hängt den Ausgang eines Sicherheitszugriffs an die Kette.
    ///
    /// Der Eintrag nennt Ebene und Ausgang — **keinen** Seed, **keinen**
    /// Schlüssel. Die Kette ist ein Nachweis, den man weitergibt (Support,
    /// Datei, Ticket); ein Geheimnis darin wäre ein weitergegebenes Geheimnis.
    /// Steht nur der Dienst drin, belegt der Eintrag den Vorgang, ohne ihn zu
    /// verraten.
    ///
    /// Auch der Fehlversuch wird protokolliert: genau den will man später
    /// erklärt haben. „Bereits offen“ und „entsperrt“ bekommen eigene Sätze,
    /// weil beides verschiedene Vorgänge sind.
    ///
    /// Hier und nicht im Kern, weil dieser Eintrag der einzige Weg der App zu
    /// `27` ist: `Sicherheit` wird mit der Kette gebaut, es gibt also keinen
    /// Aufruf, der entsperrt und nichts protokolliert.
    fn protokolliere(
        &self,
        kette: &mut AuditKette,
        ebene: u8,
        ergebnis: &std::result::Result<vci_core::uds::SecurityZugriff, vci_core::uds::UdsFehler>,
    ) {
        let dienst = match ergebnis {
            Ok(vci_core::uds::SecurityZugriff::BereitsOffen) => {
                format!("SecurityAccess Ebene 0x{ebene:02X} war bereits offen (27 {ebene:02X})")
            }
            Ok(vci_core::uds::SecurityZugriff::Entsperrt) => {
                format!("SecurityAccess Ebene 0x{ebene:02X} entsperrt (27 {ebene:02X})")
            }
            // Beim Fehler bleibt die Ebene stehen; der Grund kommt aus dem
            // Klartext des Fehlers (etwa „NRC 0x35: invalidKey“).
            Err(_) => format!("SecurityAccess Ebene 0x{ebene:02X}"),
        };
        let ausgang = match ergebnis {
            Ok(_) => Ergebnis::Erfolg,
            Err(e) => Ergebnis::Fehler {
                grund: e.to_string(),
            },
        };
        kette.anhaengen(
            jetzt_ms(),
            &self.vin,
            self.inner.iso().req_id(),
            &dienst,
            ausgang,
        );
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

/// Ein Eintrag der Audit-Kette, wie ihn die App anzeigt.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AuditEintragFfi {
    pub laufnummer: u32,
    pub zeit_ms: u64,
    pub vin: String,
    pub steuergeraet: u32,
    pub dienst: String,
    /// „Erfolg" oder „Fehler: …" — als Text, damit die Liste ohne Tabelle
    /// lesbar bleibt.
    pub ergebnis: String,
    pub hash: String,
    pub vorheriger_hash: String,
}

/// Die Audit-Kette als Objekt über die Sprachgrenze.
///
/// Die Kette gehört in die App, nicht in jede Funktion: sie überlebt mehrere
/// Abläufe und muss am Ende exportierbar sein. `Mutex`, weil UniFFI-Objekte
/// von mehreren Threads aufgerufen werden dürfen.
#[derive(uniffi::Object)]
pub struct Audit {
    inner: Mutex<AuditKette>,
}

#[uniffi::export]
impl Audit {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Audit {
            inner: Mutex::new(AuditKette::neu()),
        })
    }

    /// Hängt einen Eintrag an und gibt seine Laufnummer zurück.
    ///
    /// `grund` wird nur bei `erfolg == false` verwendet — ein Erfolg mit
    /// Begründung wäre im Export irreführend.
    #[allow(clippy::too_many_arguments)]
    pub fn anhaengen(
        &self,
        zeit_ms: u64,
        vin: String,
        steuergeraet: u32,
        dienst: String,
        erfolg: bool,
        grund: String,
    ) -> Result<u32, VciError> {
        let mut kette = self.inner.lock().map_err(|_| VciError::Io)?;
        let ergebnis = if erfolg {
            Ergebnis::Erfolg
        } else {
            Ergebnis::Fehler { grund }
        };
        Ok(kette.anhaengen(zeit_ms, &vin, steuergeraet, &dienst, ergebnis))
    }

    /// Prüft Laufnummern, Verkettung und jeden Hash.
    pub fn pruefen(&self) -> Result<(), VciError> {
        let kette = self.inner.lock().map_err(|_| VciError::Io)?;
        kette.pruefen().map_err(VciError::from)
    }

    pub fn als_json(&self) -> String {
        match self.inner.lock() {
            Ok(kette) => kette.als_json(),
            Err(_) => String::from("{\n  \"eintraege\": [],\n  \"letzter_hash\": \"\"\n}\n"),
        }
    }

    pub fn letzter_hash(&self) -> String {
        match self.inner.lock() {
            Ok(kette) => vci_core::audit::hex32(&kette.letzter_hash()),
            Err(_) => String::new(),
        }
    }

    pub fn len(&self) -> u32 {
        match self.inner.lock() {
            Ok(kette) => kette.len() as u32,
            Err(_) => 0,
        }
    }

    /// Leer heißt: noch kein Vorgang protokolliert.
    ///
    /// Steht neben `len`, weil Clippy sonst zu Recht fragt, warum es nur die
    /// eine Hälfte gibt — und weil die App die leere Kette unterscheiden muss
    /// von einer, die nicht gelesen werden konnte.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn eintraege(&self) -> Vec<AuditEintragFfi> {
        match self.inner.lock() {
            Ok(kette) => kette
                .eintraege()
                .iter()
                .map(|e| AuditEintragFfi {
                    laufnummer: e.laufnummer,
                    zeit_ms: e.zeit_ms,
                    vin: e.vin.clone(),
                    steuergeraet: e.steuergeraet,
                    dienst: e.dienst.clone(),
                    ergebnis: match &e.ergebnis {
                        Ergebnis::Erfolg => "Erfolg".to_string(),
                        Ergebnis::Fehler { grund } => format!("Fehler: {grund}"),
                    },
                    hash: vci_core::audit::hex32(&e.hash),
                    vorheriger_hash: vci_core::audit::hex32(&e.vorheriger_hash),
                })
                .collect(),
            Err(_) => Vec::new(),
        }
    }
}

/// Was die Runtime vom Fahrzeug und vom Bediener braucht.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FunktionsKontextFfi {
    pub vin: String,
    pub klemme15: bool,
    pub motor_aus: bool,
    /// Pflicht ab Risiko R2.
    pub bestaetigung: bool,
}

/// Ergebnis eines durchgelaufenen Ablaufs.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AusfuehrungFfi {
    pub funktion: String,
    pub schritte: u32,
    pub backup_dids: Vec<u32>,
    pub backup_hex: Vec<String>,
    pub audit_laufnummern: Vec<u32>,
}

/// Eine Vorbedingung als Daten, damit die App sie **vorab** anzeigen kann.
///
/// Nur so kann der Bildschirm „Klemme 15 fehlt“ schreiben, bevor ein Rahmen
/// hinausgeht — eine Prüfung erst im Kern wäre für den Bediener zu spät.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VorbedingungFfi {
    /// `klemme15`, `motor_aus` oder `bordspannung`.
    pub art: String,
    /// Nur bei `bordspannung` gesetzt, sonst 0.
    pub min_mv: u32,
    pub max_mv: u32,
}

/// Ein Schritt als Daten — für die Liste „was wird gesendet“.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SchrittFfi {
    /// Ab 1 gezählt, wie in der Anzeige.
    pub nummer: u32,
    pub anfrage_hex: String,
    pub erwartet_hex: String,
    pub timeout_ms: u32,
}

/// Hex mit Leerzeichen zwischen den Bytes (`10 03`) — so, wie es im
/// Prüfbericht steht. `sfd::hex` liefert `1003`; das ist für ein Protokoll
/// richtig, für eine Liste am Bildschirm schlecht lesbar.
fn hex_paare(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| format!("{b:02X}"))
        .collect::<Vec<_>>()
        .join(" ")
}

/// Eine Werkstattfunktion aus Text — geprüft und ausführbar.
///
/// Der Text kommt aus dem Content-Ordner (YAML-Teilmenge, siehe
/// `core/src/content.rs`). Unbekannte Schlüssel werden abgelehnt, nicht
/// ignoriert: ein Tippfehler darf keine Vorbedingung stillschweigend
/// abschalten.
#[derive(uniffi::Object)]
pub struct Funktion {
    inner: FunctionDef,
}

#[uniffi::export]
impl Funktion {
    /// Liest eine Definition; `datei` erscheint in Fehlermeldungen.
    #[uniffi::constructor]
    pub fn aus_yaml(datei: String, text: String) -> Result<Arc<Self>, VciError> {
        let def = vci_core::content::laden(&datei, &text).map_err(VciError::from)?;
        Ok(Arc::new(Funktion { inner: def }))
    }

    pub fn id(&self) -> String {
        self.inner.id.clone()
    }

    pub fn titel(&self) -> String {
        self.inner.titel.clone()
    }

    /// Risikoklasse als Text (`R0`…`R4`).
    pub fn risk(&self) -> String {
        self.inner.risk.als_text().to_string()
    }

    pub fn braucht_bestaetigung(&self) -> bool {
        self.inner.risk.braucht_bestaetigung()
    }

    pub fn schritte(&self) -> u32 {
        self.inner.steps.len() as u32
    }

    /// Vorbedingungen als Daten — die App prüft sie vorab an.
    pub fn vorbedingungen(&self) -> Vec<VorbedingungFfi> {
        self.inner
            .preconditions
            .iter()
            .map(|p| match p {
                vci_core::function::Precondition::Klemme15 => VorbedingungFfi {
                    art: "klemme15".to_string(),
                    min_mv: 0,
                    max_mv: 0,
                },
                vci_core::function::Precondition::MotorAus => VorbedingungFfi {
                    art: "motor_aus".to_string(),
                    min_mv: 0,
                    max_mv: 0,
                },
                vci_core::function::Precondition::Bordspannung { min_mv, max_mv } => {
                    VorbedingungFfi {
                        art: "bordspannung".to_string(),
                        min_mv: *min_mv,
                        max_mv: *max_mv,
                    }
                }
            })
            .collect()
    }

    /// Die Schritte, die der Ablauf senden wird (ohne Notausstieg).
    pub fn schrittliste(&self) -> Vec<SchrittFfi> {
        self.inner
            .steps
            .iter()
            .enumerate()
            .map(|(i, s)| SchrittFfi {
                nummer: i as u32 + 1,
                anfrage_hex: hex_paare(&s.anfrage),
                erwartet_hex: hex_paare(&s.erwartet),
                timeout_ms: s.timeout_ms,
            })
            .collect()
    }

    pub fn pruefe_schema(&self) -> Result<(), VciError> {
        self.inner
            .pruefe_schema()
            .map_err(|fehler| VciError::Protocol {
                detail: fehler
                    .iter()
                    .map(|f| f.to_string())
                    .collect::<Vec<_>>()
                    .join("; "),
            })
    }

    pub fn pruefe_freigabe(&self) -> Result<(), VciError> {
        self.inner
            .pruefe_freigabe()
            .map_err(|fehler| VciError::Protocol {
                detail: fehler
                    .iter()
                    .map(|f| f.to_string())
                    .collect::<Vec<_>>()
                    .join("; "),
            })
    }

    /// Führt den Ablauf aus; jeder Schritt landet in `audit`.
    pub fn ausfuehren(
        &self,
        transport: Box<dyn CanTransport>,
        kontext: FunktionsKontextFfi,
        audit: Arc<Audit>,
        zeit_ms: u64,
    ) -> Result<AusfuehrungFfi, VciError> {
        let mut t = ForeignTransport::new(transport.as_ref());
        let ctx = Kontext {
            vin: kontext.vin,
            klemme15: kontext.klemme15,
            motor_aus: kontext.motor_aus,
            bestaetigung: kontext.bestaetigung,
        };
        let client = vci_core::UdsClient::new();
        let mut kette = audit.inner.lock().map_err(|_| VciError::Io)?;
        let ergebnis = self
            .inner
            .ausfuehren(&client, &mut t, &ctx, &mut kette, zeit_ms);

        t.finish_funktion(ergebnis).map(|a| AusfuehrungFfi {
            funktion: a.funktion,
            schritte: a.schritte as u32,
            backup_dids: a.backup.iter().map(|(did, _)| *did as u32).collect(),
            backup_hex: a
                .backup
                .iter()
                .map(|(_, daten)| vci_core::sfd::hex(daten))
                .collect(),
            audit_laufnummern: a.audit_laufnummern,
        })
    }
}

#[cfg(test)]
mod tests;
