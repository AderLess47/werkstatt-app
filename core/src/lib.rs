//! `vci-core` — der App-Kern.
//!
//! Aufbau in Abhängigkeitsreihenfolge:
//!
//! 1. [`transport`] — die einzige Stelle, die Hardware kennt (BLE, USB, J2534).
//! 2. [`isotp`] — ISO 15765-2, Segmentierung über CAN.
//! 3. [`uds`] — ISO 14229-1: Sitzung, Kennungen, NRC-Behandlung.
//! 4. [`j1979`] — Fehlercodes auswerten: `P0301`, Statusbit als Wort.
//! 5. [`sfd`] — VAG „Schutz Fahrzeug Diagnose“ über UDS.
//! 6. [`content`] — Werkstattfunktionen aus Text lesen (Schema v0.1).
//! 7. [`function`] — die Funktion ausführen: Vorbedingungen, Schritte, Notausstieg.
//! 8. [`audit`] — SHA-256-Hash-Kette über jede Operation.
//!
//! Der Kern kennt weder UI noch Plattform. Android und iOS bekommen ihn über
//! UniFFI; die Windows-DLL bleibt eine separate C-Implementierung
//! (`j2534/`), deren Verhalten hier als Konformitätskorpus dient.

pub mod audit;
pub mod content;
pub mod function;
pub mod isotp;
pub mod j1979;
pub mod loopback;
pub mod sfd;
pub mod transport;
pub mod uds;

pub use audit::{AuditEintrag, AuditFehler, AuditKette, Ergebnis};
pub use content::{laden as funktion_laden, InhaltsFehler};
pub use function::{
    AusfuehrungsFehler, Freigabe, FunctionDef, Kontext, Precondition, Provenance, Risk, Step,
    ValidierungsFehler, VehicleMatch,
};
pub use isotp::IsotpChannel;
pub use j1979::{DtcAnzeige, J1979Fehler};
pub use loopback::Loopback;
pub use sfd::{SfdClient, SfdError, SfdFehler};
pub use transport::{CanFrame, Capabilities, Result, Transport, TransportError};
pub use uds::{UdsClient, UdsFehler};
