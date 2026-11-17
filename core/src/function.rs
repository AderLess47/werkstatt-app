//! Werkstattfunktionen als **Daten**, nicht als Code je Marke.
//!
//! ## Warum Daten
//!
//! 300 Funktionen × 40 Marken sind als Code nicht pflegbar: jede Änderung wäre
//! ein neuer Build, jede Freigabe ein neuer Datensatz. Hier beschreibt eine
//! Definition den Ablauf (Anfrage, erwartete Antwort, Frist, Notausstieg), und
//! die Runtime führt ihn aus — mit denselben Prüfungen für alle Funktionen.
//!
//! ## Sicherheitsregeln, die im Code durchgesetzt werden
//!
//! - **Risikoklasse**: `R0` liest nur, `R1` schreibt reversibel, `R2`–`R4`
//!   brauchen eine ausdrückliche Bestätigung des Bedieners. Ohne
//!   [`Kontext::bestaetigung`] läuft keine Funktion ab `R2`.
//! - **Freigabestufe der Inhalte**: eine Definition mit Risiko ab `R2` und
//!   `Freigabe::Unbelegt` wird abgelehnt. Ein Entwurf darf nicht versehentlich
//!   am Fahrzeug laufen, nur weil ihn jemand ins Verzeichnis gelegt hat.
//! - **Audit zuerst**: jeder Schritt erzeugt einen Eintrag in der Hash-Kette —
//!   auch der, der scheitert. Ein Ablauf, dessen Schritte nicht vollständig in
//!   der Kette stehen, gilt nicht als erfolgreich ([`AusfuehrungsFehler::NichtProtokolliert`]).
//! - **Kein Raten bei Antworten**: die erwartete Antwort wird als **Präfix**
//!   geprüft, weil positive Antworten je Steuergerät längere Nutzlast tragen.
//!   Ein leerer Erwartungswert ist unzulässig — er würde alles durchwinken.
//!
//! ## Was hier nicht drin ist
//!
//! Seed/Key-Algorithmen und Backup-Formate je Hersteller. Das Backup liest nur
//! die angegebenen Kennungen und legt sie im Ergebnis ab; was damit passiert,
//! entscheidet die App.

use std::fmt;

use crate::audit::{AuditKette, Ergebnis};
use crate::sfd::aus_hex;
use crate::transport::{Transport, TransportError};
use crate::uds::pruefe_nrc;
use crate::uds::{UdsClient, UdsFehler};

/// Risikoklasse einer Funktion.
///
/// Die Stufen sind aus dem Plan übernommen: `R0` lesen, `R1` schreiben
/// reversibel, `R2` schreiben mit Rückwirkung, `R3` sicherheitsrelevant,
/// `R4` nur mit Herstellerfreigabe.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Risk {
    R0,
    R1,
    R2,
    R3,
    R4,
}

impl Risk {
    /// Liest die Stufe aus Text (`"R2"`); unbekannt ergibt `None`.
    pub fn aus_text(text: &str) -> Option<Risk> {
        match text.trim() {
            "R0" => Some(Risk::R0),
            "R1" => Some(Risk::R1),
            "R2" => Some(Risk::R2),
            "R3" => Some(Risk::R3),
            "R4" => Some(Risk::R4),
            _ => None,
        }
    }

    pub fn als_text(&self) -> &'static str {
        match self {
            Risk::R0 => "R0",
            Risk::R1 => "R1",
            Risk::R2 => "R2",
            Risk::R3 => "R3",
            Risk::R4 => "R4",
        }
    }

    /// Ab `R2` muss der Bediener bestätigen — vor dem ersten Rahmen.
    pub fn braucht_bestaetigung(&self) -> bool {
        *self >= Risk::R2
    }

    /// Schreibt diese Stufe? `R0` liest nur.
    pub fn schreibt(&self) -> bool {
        *self >= Risk::R1
    }
}

/// Freigabestufe der **Inhalte** (nicht des Ablaufs).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Freigabe {
    /// Entwurf ohne Beleg am Fahrzeug.
    Unbelegt,
    /// Am Prüfstand oder Fahrzeug einmal bestätigt.
    Geprueft,
    /// Fachlich freigegeben für die Werkstatt.
    Freigegeben,
}

impl Freigabe {
    pub fn aus_text(text: &str) -> Option<Freigabe> {
        match text.trim() {
            "unbelegt" => Some(Freigabe::Unbelegt),
            "geprüft" | "geprueft" => Some(Freigabe::Geprueft),
            "freigegeben" => Some(Freigabe::Freigegeben),
            _ => None,
        }
    }

    pub fn als_text(&self) -> &'static str {
        match self {
            Freigabe::Unbelegt => "unbelegt",
            Freigabe::Geprueft => "geprüft",
            Freigabe::Freigegeben => "freigegeben",
        }
    }
}

/// Herkunft und Freigabe einer Definition.
///
/// `evidence_hash` bindet die Definition an den Nachweis (Log, Messprotokoll).
/// Leer ist erlaubt, solange die Freigabe `unbelegt` ist — genau diese
/// Kombination kennzeichnet einen Entwurf.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Provenance {
    pub quelle: String,
    pub freigabe: Freigabe,
    pub evidence_hash: String,
}

/// Wann eine Funktion zu einem Fahrzeug passt.
///
/// Alle gesetzten Felder müssen passen; nicht gesetzte Felder schränken nicht
/// ein. Ein falscher Treffer führt später zu einer Funktion am falschen
/// Fahrzeug — deshalb lieber `None` als ein geratener Wert.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct VehicleMatch {
    pub vin_praefix: Option<String>,
    pub hsn: Option<String>,
    pub tsn: Option<String>,
    /// Adressen der Steuergeräte, die vorhanden sein müssen.
    pub steuergeraete: Vec<u32>,
}

impl VehicleMatch {
    /// Prüft die Identifikationsmerkmale (Steuergeräte prüft der Aufrufer).
    pub fn passt(&self, vin: &str, hsn: Option<&str>, tsn: Option<&str>) -> bool {
        if let Some(praefix) = &self.vin_praefix {
            if !vin.starts_with(praefix.as_str()) {
                return false;
            }
        }
        if let Some(erwartet) = &self.hsn {
            if hsn != Some(erwartet.as_str()) {
                return false;
            }
        }
        if let Some(erwartet) = &self.tsn {
            if tsn != Some(erwartet.as_str()) {
                return false;
            }
        }
        true
    }

    /// Ist überhaupt eine Einschränkung gesetzt?
    pub fn ist_leer(&self) -> bool {
        self.vin_praefix.is_none()
            && self.hsn.is_none()
            && self.tsn.is_none()
            && self.steuergeraete.is_empty()
    }
}

/// Bedingung, die vor dem ersten Rahmen erfüllt sein muss.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Precondition {
    /// Klemme 15 an.
    Klemme15,
    /// Motor steht.
    MotorAus,
    /// Bordspannung im Fenster (in mV).
    Bordspannung { min_mv: u32, max_mv: u32 },
}

/// Ein Schritt: Anfrage, erwartete Antwort, Frist, Notausstieg.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Step {
    /// Rohbytes der UDS-Anfrage.
    pub anfrage: Vec<u8>,
    /// Erwarteter **Anfang** der Antwort.
    pub erwartet: Vec<u8>,
    /// Frist für die Antwort.
    pub timeout_ms: u32,
    /// Schritte, die bei einem Fehlschlag noch gesendet werden (z. B. „Sitzung
    /// wechseln und sperren"). Leer heißt: es gibt keinen Notausstieg.
    pub recovery: Vec<Step>,
}

impl Step {
    /// Kurzform für Inhalte und Tests.
    pub fn neu(anfrage: &[u8], erwartet: &[u8], timeout_ms: u32) -> Self {
        Step {
            anfrage: anfrage.to_vec(),
            erwartet: erwartet.to_vec(),
            timeout_ms,
            recovery: Vec::new(),
        }
    }

    /// Text für den Audit-Eintrag: Dienst plus Anfrage in Hex.
    pub fn beschreibung(&self) -> String {
        let dienst = match self.anfrage.first() {
            Some(0x10) => "session",
            Some(0x11) => "reset",
            Some(0x14) => "clear_dtc",
            Some(0x19) => "read_dtc",
            Some(0x22) => "read_did",
            Some(0x2E) => "write_did",
            Some(0x31) => "routine",
            Some(0x3E) => "tester_present",
            _ => "uds",
        };
        format!("{dienst} {}", hex_lesbar(&self.anfrage))
    }
}

/// Was vor dem ersten Schreibzugriff gesichert wird.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BackupSpec {
    /// Kennungen, die vorher gelesen werden.
    pub dids: Vec<u16>,
}

/// Eine Werkstattfunktion als Daten.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FunctionDef {
    pub id: String,
    pub titel: String,
    pub risk: Risk,
    pub match_rule: VehicleMatch,
    pub preconditions: Vec<Precondition>,
    pub steps: Vec<Step>,
    pub backup: Option<BackupSpec>,
    pub provenance: Provenance,
}

/// Was an einer Definition nicht stimmt.
///
/// Ein Vektor, kein Einzelfehler: beim Laden eines Inhaltsverzeichnisses will
/// man alle Mängel auf einmal sehen, nicht einen je Lauf.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ValidierungsFehler {
    IdLeer,
    IdNichtKlein { id: String },
    TitelLeer,
    KeineSchritte,
    MatchRegelLeer,
    SchrittOhneAnfrage { schritt: usize },
    ErwartungLeer { schritt: usize },
    TimeoutNull { schritt: usize },
    AnfrageZuLang { schritt: usize, laenge: usize },
    RecoveryZuTief { schritt: usize },
    BordspannungUngueltig { min_mv: u32, max_mv: u32 },
    FreigabeFehltFuerRisiko { risk: Risk, freigabe: Freigabe },
    EvidenceFehlt { freigabe: Freigabe },
    EvidenceKeinHex { evidence_hash: String },
}

impl fmt::Display for ValidierungsFehler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ValidierungsFehler::IdLeer => write!(f, "id fehlt"),
            ValidierungsFehler::IdNichtKlein { id } => write!(
                f,
                "id „{id}\" ist nicht durchgehend klein geschrieben (a–z, 0–9, -)"
            ),
            ValidierungsFehler::TitelLeer => write!(f, "titel fehlt"),
            ValidierungsFehler::KeineSchritte => write!(f, "keine Schritte definiert"),
            ValidierungsFehler::MatchRegelLeer => write!(
                f,
                "match ist leer — die Funktion würde auf jedes Fahrzeug passen"
            ),
            ValidierungsFehler::SchrittOhneAnfrage { schritt } => {
                write!(f, "Schritt {schritt}: anfrage fehlt")
            }
            ValidierungsFehler::ErwartungLeer { schritt } => write!(
                f,
                "Schritt {schritt}: erwartet fehlt — eine leere Erwartung würde alles durchwinken"
            ),
            ValidierungsFehler::TimeoutNull { schritt } => {
                write!(f, "Schritt {schritt}: timeout_ms ist 0")
            }
            ValidierungsFehler::AnfrageZuLang { schritt, laenge } => write!(
                f,
                "Schritt {schritt}: Anfrage mit {laenge} Byte passt nicht in eine ISO-TP-Nachricht"
            ),
            ValidierungsFehler::RecoveryZuTief { schritt } => write!(
                f,
                "Schritt {schritt}: Notausstieg darf keinen eigenen Notausstieg haben"
            ),
            ValidierungsFehler::BordspannungUngueltig { min_mv, max_mv } => write!(
                f,
                "Bordspannung: min {min_mv} mV ist nicht kleiner als max {max_mv} mV"
            ),
            ValidierungsFehler::FreigabeFehltFuerRisiko { risk, freigabe } => write!(
                f,
                "Risiko {} braucht eine Freigabe über „{}\"",
                risk.als_text(),
                freigabe.als_text()
            ),
            ValidierungsFehler::EvidenceFehlt { freigabe } => write!(
                f,
                "Freigabe „{}\" ohne evidence_hash — ohne Nachweis keine Freigabe",
                freigabe.als_text()
            ),
            ValidierungsFehler::EvidenceKeinHex { evidence_hash } => {
                write!(f, "evidence_hash „{evidence_hash}\" ist kein Hex-Text")
            }
        }
    }
}

impl FunctionDef {
    /// Strukturelle Prüfung: alles, was nicht von der Freigabe abhängt.
    pub fn pruefe_schema(&self) -> std::result::Result<(), Vec<ValidierungsFehler>> {
        let mut fehler = Vec::new();

        if self.id.is_empty() {
            fehler.push(ValidierungsFehler::IdLeer);
        } else if !self
            .id
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
        {
            fehler.push(ValidierungsFehler::IdNichtKlein {
                id: self.id.clone(),
            });
        }
        if self.titel.trim().is_empty() {
            fehler.push(ValidierungsFehler::TitelLeer);
        }
        if self.match_rule.ist_leer() {
            fehler.push(ValidierungsFehler::MatchRegelLeer);
        }
        if self.steps.is_empty() {
            fehler.push(ValidierungsFehler::KeineSchritte);
        }

        for bedingung in &self.preconditions {
            if let Precondition::Bordspannung { min_mv, max_mv } = bedingung {
                if min_mv >= max_mv {
                    fehler.push(ValidierungsFehler::BordspannungUngueltig {
                        min_mv: *min_mv,
                        max_mv: *max_mv,
                    });
                }
            }
        }

        for (i, schritt) in self.steps.iter().enumerate() {
            fehler.extend(pruefe_schritt(schritt, i));
        }

        if fehler.is_empty() {
            Ok(())
        } else {
            Err(fehler)
        }
    }

    /// Prüft die Freigabe gegen die Risikoklasse.
    ///
    /// Getrennt von [`FunctionDef::pruefe_schema`], weil ein Entwurf
    /// strukturell gültig und trotzdem nicht freigegeben sein kann — die
    /// Meldung soll dann „nicht freigegeben" heißen und nicht „kaputt".
    pub fn pruefe_freigabe(&self) -> std::result::Result<(), Vec<ValidierungsFehler>> {
        let mut fehler = Vec::new();
        let freigabe = self.provenance.freigabe;

        if self.risk >= Risk::R2 && freigabe == Freigabe::Unbelegt {
            fehler.push(ValidierungsFehler::FreigabeFehltFuerRisiko {
                risk: self.risk,
                freigabe,
            });
        }
        if freigabe != Freigabe::Unbelegt {
            if self.provenance.evidence_hash.is_empty() {
                fehler.push(ValidierungsFehler::EvidenceFehlt { freigabe });
            } else if aus_hex(&self.provenance.evidence_hash).is_err()
                || self.provenance.evidence_hash.len() % 2 != 0
            {
                fehler.push(ValidierungsFehler::EvidenceKeinHex {
                    evidence_hash: self.provenance.evidence_hash.clone(),
                });
            }
        }

        if fehler.is_empty() {
            Ok(())
        } else {
            Err(fehler)
        }
    }

    /// Darf diese Definition ohne Rückfrage laufen?
    pub fn laeuft_ohne_bestaetigung(&self) -> bool {
        !self.risk.braucht_bestaetigung()
    }
}

fn pruefe_schritt(schritt: &Step, nummer: usize) -> Vec<ValidierungsFehler> {
    let mut fehler = Vec::new();
    if schritt.anfrage.is_empty() {
        fehler.push(ValidierungsFehler::SchrittOhneAnfrage { schritt: nummer });
    } else if schritt.anfrage.len() > crate::isotp::MAX_PAYLOAD {
        fehler.push(ValidierungsFehler::AnfrageZuLang {
            schritt: nummer,
            laenge: schritt.anfrage.len(),
        });
    }
    if schritt.erwartet.is_empty() {
        fehler.push(ValidierungsFehler::ErwartungLeer { schritt: nummer });
    }
    if schritt.timeout_ms == 0 {
        fehler.push(ValidierungsFehler::TimeoutNull { schritt: nummer });
    }
    for recovery in &schritt.recovery {
        if !recovery.recovery.is_empty() {
            fehler.push(ValidierungsFehler::RecoveryZuTief { schritt: nummer });
        }
        // Der Notausstieg wird selbst wie ein Schritt geprüft: eine Anfrage
        // ohne Erwartung wäre auch dort ein Blindflug.
        fehler.extend(
            pruefe_schritt(recovery, nummer)
                .into_iter()
                .filter(|f| !matches!(f, ValidierungsFehler::RecoveryZuTief { .. })),
        );
    }
    fehler
}

/// Was die Runtime vom Fahrzeug und vom Bediener braucht.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Kontext {
    /// VIN, wie sie gelesen wurde; leer, wenn unbekannt.
    pub vin: String,
    /// Klemme 15 (Zündung) liegt an.
    pub klemme15: bool,
    /// Der Motor steht.
    pub motor_aus: bool,
    /// Der Bediener hat den Ablauf bestätigt (Pflicht ab `R2`).
    pub bestaetigung: bool,
}

impl Kontext {
    /// Der Normalfall im Test: Zündung an, Motor aus, kein Bestätigen.
    pub fn neu(vin: &str) -> Self {
        Kontext {
            vin: vin.to_string(),
            klemme15: true,
            motor_aus: true,
            bestaetigung: false,
        }
    }

    pub fn mit_bestaetigung(mut self, ja: bool) -> Self {
        self.bestaetigung = ja;
        self
    }

    pub fn mit_klemme15(mut self, an: bool) -> Self {
        self.klemme15 = an;
        self
    }

    pub fn mit_motor(mut self, aus: bool) -> Self {
        self.motor_aus = aus;
        self
    }
}

/// Ergebnis eines durchgelaufenen Ablaufs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ausfuehrung {
    /// Kennung der Funktion.
    pub funktion: String,
    /// Wie viele Schritte gesendet wurden.
    pub schritte: usize,
    /// Gelesene Kennungen vor dem ersten Schreibzugriff.
    pub backup: Vec<(u16, Vec<u8>)>,
    /// Laufnummern der erzeugten Audit-Einträge.
    pub audit_laufnummern: Vec<u32>,
}

impl Ausfuehrung {
    /// Steht zu jedem Schritt ein Audit-Eintrag? Nur dann gilt der Ablauf als
    /// erfolgreich.
    pub fn ist_vollstaendig_protokolliert(&self) -> bool {
        self.audit_laufnummern.len() == self.schritte
    }
}

/// Warum ein Ablauf nicht durchgelaufen ist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AusfuehrungsFehler {
    /// Die Definition selbst ist nicht in Ordnung.
    Validierung(Vec<ValidierungsFehler>),
    /// Eine Vorbedingung war nicht erfüllt.
    Vorbedingung { grund: String },
    /// Risiko ab `R2` ohne Bestätigung des Bedieners.
    BestaetigungFehlt { risk: Risk },
    /// Transportfehler (Kabel, Timeout).
    Transport(TransportError),
    /// Negative Antwort oder Protokollfehler im UDS-Pfad.
    Uds(UdsFehler),
    /// Die Antwort begann nicht wie erwartet.
    AntwortPasstNicht {
        schritt: usize,
        erwartet: String,
        gesehen: String,
    },
    /// Das Backup ließ sich nicht lesen — dann wird nicht geschrieben.
    BackupFehlgeschlagen { did: u16, grund: String },
    /// Der Notausstieg ist selbst gescheitert.
    RecoveryFehlgeschlagen { schritt: usize, grund: String },
    /// Die Audit-Kette passt nicht — der Ablauf gilt nicht als abgeschlossen.
    NichtProtokolliert,
}

impl fmt::Display for AusfuehrungsFehler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AusfuehrungsFehler::Validierung(fehler) => {
                let texte: Vec<String> = fehler.iter().map(|f| f.to_string()).collect();
                write!(f, "Definition ungültig: {}", texte.join("; "))
            }
            AusfuehrungsFehler::Vorbedingung { grund } => {
                write!(f, "Vorbedingung nicht erfüllt: {grund}")
            }
            AusfuehrungsFehler::BestaetigungFehlt { risk } => write!(
                f,
                "Risiko {} verlangt eine Bestätigung des Bedieners",
                risk.als_text()
            ),
            AusfuehrungsFehler::Transport(e) => write!(f, "{e}"),
            AusfuehrungsFehler::Uds(e) => write!(f, "{e}"),
            AusfuehrungsFehler::AntwortPasstNicht {
                schritt,
                erwartet,
                gesehen,
            } => write!(
                f,
                "Schritt {schritt}: Antwort beginnt nicht mit {erwartet} (gesehen: {gesehen})"
            ),
            AusfuehrungsFehler::BackupFehlgeschlagen { did, grund } => {
                write!(f, "Backup von Kennung {did:04X} fehlgeschlagen: {grund}")
            }
            AusfuehrungsFehler::RecoveryFehlgeschlagen { schritt, grund } => {
                write!(
                    f,
                    "Notausstieg nach Schritt {schritt} fehlgeschlagen: {grund}"
                )
            }
            AusfuehrungsFehler::NichtProtokolliert => {
                write!(
                    f,
                    "Ablauf ohne vollständigen Audit-Eintrag — nicht abgeschlossen"
                )
            }
        }
    }
}

impl std::error::Error for AusfuehrungsFehler {}

impl From<TransportError> for AusfuehrungsFehler {
    fn from(e: TransportError) -> Self {
        AusfuehrungsFehler::Transport(e)
    }
}

impl From<UdsFehler> for AusfuehrungsFehler {
    fn from(e: UdsFehler) -> Self {
        AusfuehrungsFehler::Uds(e)
    }
}

impl FunctionDef {
    /// Prüft Vorbedingungen gegen Kontext und Fahrzeug.
    ///
    /// Die Bordspannung kommt vom Transport, nicht aus dem Kontext: sie soll
    /// gemessen werden, nicht behauptet. Ein Gerät, das sie nicht messen kann,
    /// lässt eine Funktion mit Spannungsbedingung **nicht** laufen — „unbekannt"
    /// ist nicht „in Ordnung".
    pub fn pruefe_vorbedingungen<T: Transport>(
        &self,
        t: &mut T,
        kontext: &Kontext,
    ) -> std::result::Result<(), AusfuehrungsFehler> {
        for bedingung in &self.preconditions {
            match bedingung {
                Precondition::Klemme15 => {
                    if !kontext.klemme15 {
                        return Err(AusfuehrungsFehler::Vorbedingung {
                            grund: "Klemme 15 liegt nicht an".to_string(),
                        });
                    }
                }
                Precondition::MotorAus => {
                    if !kontext.motor_aus {
                        return Err(AusfuehrungsFehler::Vorbedingung {
                            grund: "Motor läuft".to_string(),
                        });
                    }
                }
                Precondition::Bordspannung { min_mv, max_mv } => {
                    let ist = t.vbat_mv().map_err(|e| AusfuehrungsFehler::Vorbedingung {
                        grund: format!("Bordspannung nicht messbar: {e}"),
                    })?;
                    if ist < *min_mv || ist > *max_mv {
                        return Err(AusfuehrungsFehler::Vorbedingung {
                            grund: format!(
                                "Bordspannung {ist} mV liegt außerhalb {min_mv}…{max_mv} mV"
                            ),
                        });
                    }
                }
            }
        }
        Ok(())
    }

    /// Führt die Funktion aus.
    ///
    /// Reihenfolge ist Teil der Sicherheit: prüfen → bestätigen lassen →
    /// Vorbedingungen → Backup → Schritte. Ein Backup **nach** dem ersten
    /// Schreibzugriff wäre keins.
    pub fn ausfuehren<T: Transport>(
        &self,
        client: &UdsClient,
        t: &mut T,
        kontext: &Kontext,
        kette: &mut AuditKette,
        zeit_ms: u64,
    ) -> std::result::Result<Ausfuehrung, AusfuehrungsFehler> {
        let mut fehler = Vec::new();
        if let Err(e) = self.pruefe_schema() {
            fehler.extend(e);
        }
        if let Err(e) = self.pruefe_freigabe() {
            fehler.extend(e);
        }
        if !fehler.is_empty() {
            return Err(AusfuehrungsFehler::Validierung(fehler));
        }

        if self.risk.braucht_bestaetigung() && !kontext.bestaetigung {
            // Auch das wird protokolliert: ein abgelehnter Ablauf ist ein
            // Vorgang, der im Prüfbericht auftauchen muss.
            kette.anhaengen(
                zeit_ms,
                &kontext.vin,
                client.iso().req_id(),
                &format!("{} abgelehnt: Bestätigung fehlt", self.id),
                Ergebnis::Fehler {
                    grund: format!("Risiko {} ohne Bestätigung", self.risk.als_text()),
                },
            );
            return Err(AusfuehrungsFehler::BestaetigungFehlt { risk: self.risk });
        }

        if let Err(e) = self.pruefe_vorbedingungen(t, kontext) {
            kette.anhaengen(
                zeit_ms,
                &kontext.vin,
                client.iso().req_id(),
                &format!("{} abgelehnt: Vorbedingung", self.id),
                Ergebnis::Fehler {
                    grund: e.to_string(),
                },
            );
            return Err(e);
        }

        // Backup vor dem ersten Schreibzugriff.
        let mut backup = Vec::new();
        if let Some(spec) = &self.backup {
            if self.risk.schreibt() {
                for did in &spec.dids {
                    match client.read_did(t, *did, BACKUP_TIMEOUT_MS) {
                        Ok(daten) => backup.push((*did, daten)),
                        Err(e) => {
                            let grund = e.to_string();
                            kette.anhaengen(
                                zeit_ms,
                                &kontext.vin,
                                client.iso().req_id(),
                                &format!("backup {:04X}", did),
                                Ergebnis::Fehler {
                                    grund: grund.clone(),
                                },
                            );
                            return Err(AusfuehrungsFehler::BackupFehlgeschlagen {
                                did: *did,
                                grund,
                            });
                        }
                    }
                }
            }
        }

        let mut laufnummern = Vec::with_capacity(self.steps.len());
        for (nummer, schritt) in self.steps.iter().enumerate() {
            let beschreibung = schritt.beschreibung();
            let antwort = client.runde(t, &schritt.anfrage, schritt.timeout_ms);
            match antwort {
                Ok(antwort) => {
                    // Eine negative Antwort ist kein „falscher Anfang", sondern
                    // eine Ablehnung — die App soll sie als NRC sehen
                    // (Dienst und Code), nicht als Protokollfehler.
                    if let Some((dienst, code)) = pruefe_nrc(&antwort) {
                        let grund = format!("abgelehnt (Dienst 0x{dienst:02X}, NRC 0x{code:02X})");
                        laufnummern.push(kette.anhaengen(
                            zeit_ms,
                            &kontext.vin,
                            client.iso().req_id(),
                            &beschreibung,
                            Ergebnis::Fehler {
                                grund: grund.clone(),
                            },
                        ));
                        // Scheitert der Notausstieg, ist *das* der Befund: ein
                        // Steuergerät, das weder ablehnt noch zurückschaltet,
                        // ist der dringendere Fall als der ursprüngliche NRC.
                        self.notausstieg(client, t, schritt, kontext, kette, zeit_ms)?;
                        return Err(AusfuehrungsFehler::Uds(UdsFehler::Nrc { dienst, code }));
                    }
                    if antwort.starts_with(&schritt.erwartet) {
                        laufnummern.push(kette.anhaengen(
                            zeit_ms,
                            &kontext.vin,
                            client.iso().req_id(),
                            &beschreibung,
                            Ergebnis::Erfolg,
                        ));
                    } else {
                        let grund = format!(
                            "erwartet {}, gesehen {}",
                            hex_lesbar(&schritt.erwartet),
                            hex_lesbar(&antwort)
                        );
                        laufnummern.push(kette.anhaengen(
                            zeit_ms,
                            &kontext.vin,
                            client.iso().req_id(),
                            &beschreibung,
                            Ergebnis::Fehler {
                                grund: grund.clone(),
                            },
                        ));
                        self.notausstieg(client, t, schritt, kontext, kette, zeit_ms)?;
                        return Err(AusfuehrungsFehler::AntwortPasstNicht {
                            schritt: nummer,
                            erwartet: hex_lesbar(&schritt.erwartet),
                            gesehen: hex_lesbar(&antwort),
                        });
                    }
                }
                Err(e) => {
                    let grund = e.to_string();
                    laufnummern.push(kette.anhaengen(
                        zeit_ms,
                        &kontext.vin,
                        client.iso().req_id(),
                        &beschreibung,
                        Ergebnis::Fehler {
                            grund: grund.clone(),
                        },
                    ));
                    if self
                        .notausstieg(client, t, schritt, kontext, kette, zeit_ms)
                        .is_err()
                    {
                        return Err(AusfuehrungsFehler::RecoveryFehlgeschlagen {
                            schritt: nummer,
                            grund,
                        });
                    }
                    return Err(AusfuehrungsFehler::Uds(e));
                }
            }
        }

        let ergebnis = Ausfuehrung {
            funktion: self.id.clone(),
            schritte: self.steps.len(),
            backup,
            audit_laufnummern: laufnummern,
        };

        // Die Regel „ohne Audit kein Erfolg" wird geprüft, nicht angenommen:
        // ein späterer Umbau, der einen Schritt ohne Eintrag ausführt, fällt
        // hier auf statt im Prüfbericht.
        if !ergebnis.ist_vollstaendig_protokolliert() || kette.pruefen().is_err() {
            return Err(AusfuehrungsFehler::NichtProtokolliert);
        }
        Ok(ergebnis)
    }

    /// Sendet den Notausstieg eines Schritts; jeder Rahmen wird protokolliert.
    fn notausstieg<T: Transport>(
        &self,
        client: &UdsClient,
        t: &mut T,
        schritt: &Step,
        kontext: &Kontext,
        kette: &mut AuditKette,
        zeit_ms: u64,
    ) -> std::result::Result<(), AusfuehrungsFehler> {
        for recovery in &schritt.recovery {
            let beschreibung = format!("recovery {}", recovery.beschreibung());
            let antwort = client.runde(t, &recovery.anfrage, recovery.timeout_ms);
            let (ergebnis, ok) = match &antwort {
                Ok(a) if a.starts_with(&recovery.erwartet) => (Ergebnis::Erfolg, true),
                Ok(a) => (
                    Ergebnis::Fehler {
                        grund: format!(
                            "erwartet {}, gesehen {}",
                            hex_lesbar(&recovery.erwartet),
                            hex_lesbar(a)
                        ),
                    },
                    false,
                ),
                Err(e) => (
                    Ergebnis::Fehler {
                        grund: e.to_string(),
                    },
                    false,
                ),
            };
            kette.anhaengen(
                zeit_ms,
                &kontext.vin,
                client.iso().req_id(),
                &beschreibung,
                ergebnis,
            );
            if !ok {
                return Err(AusfuehrungsFehler::RecoveryFehlgeschlagen {
                    schritt: 0,
                    grund: antwort
                        .map(|a| hex_lesbar(&a))
                        .unwrap_or_else(|e| e.to_string()),
                });
            }
        }
        Ok(())
    }
}

/// Hex mit Leerzeichen zwischen den Bytes — lesbar im Audit-Export.
fn hex_lesbar(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 3);
    for (i, b) in bytes.iter().enumerate() {
        if i > 0 {
            s.push(' ');
        }
        s.push_str(&format!("{b:02X}"));
    }
    s
}

/// Frist für das Backup-Lesen. Bewusst nicht je Definition konfigurierbar:
/// ein Backup ist kein Ablaufschritt, sondern eine Voraussetzung.
const BACKUP_TIMEOUT_MS: u32 = 2_000;

#[cfg(test)]
mod tests;
