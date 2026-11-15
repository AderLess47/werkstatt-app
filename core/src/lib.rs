//! `vci-core` — der App-Kern.
//!
//! Aufbau in Abhängigkeitsreihenfolge:
//!
//! 1. [`transport`] — die einzige Stelle, die Hardware kennt (BLE, USB, J2534).
//! 2. [`isotp`] — ISO 15765-2, Segmentierung über CAN.
//! 3. [`sfd`] — VAG „Schutz Fahrzeug Diagnose" über UDS (ISO 14229-1).
//! 4. `uds` — die übrigen Dienste von ISO 14229-1 (folgt).
//! 5. `function` — Werkstattfunktionen als Daten (folgt).
//! 6. `audit` — Hash-Kette über jede Schreiboperation (folgt).
//!
//! Der Kern kennt weder UI noch Plattform. Android und iOS bekommen ihn über
//! UniFFI; die Windows-DLL bleibt eine separate C-Implementierung
//! (`j2534/`), deren Verhalten hier als Konformitätskorpus dient.

pub mod isotp;
pub mod loopback;
pub mod sfd;
pub mod transport;

pub use isotp::IsotpChannel;
pub use loopback::Loopback;
pub use sfd::{SfdClient, SfdError, SfdFehler};
pub use transport::{CanFrame, Capabilities, Result, Transport, TransportError};
