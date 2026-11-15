//! Inhalte laden: Werkstattfunktionen aus Text.
//!
//! ## Warum kein YAML-Parser
//!
//! Der Kern hat keine Abhängigkeiten, und ein allgemeiner YAML-Parser wäre
//! eine große Angriffsfläche für eine kleine Aufgabe. Gelesen wird deshalb
//! **nur die Teilmenge, die das Schema braucht**:
//!
//! - eine Ebene je zwei Leerzeichen, keine Tabulatoren
//! - `schlüssel: wert` und `schlüssel:` mit Block darunter
//! - Listen als `- wert` auf eigener Zeile (keine `[a, b]`-Schreibweise)
//! - `#` am Zeilenanfang ist ein Kommentar, Zeichenketten dürfen in
//!   Anführungszeichen stehen
//!
//! Alles andere wird **abgelehnt**, nicht ignoriert. Ein Tippfehler im
//! Schlüsselnamen darf nicht dazu führen, dass eine Vorbedingung still
//! entfällt — genau das wäre am Fahrzeug gefährlich.
//!
//! ## Was hier nicht passiert
//!
//! Es wird keine Datei geöffnet: der Text kommt vom Aufrufer (App, Test,
//! Werkzeug). Damit bleibt der Kern frei von Dateisystemannahmen, und Android
//! kann die Inhalte aus dem Asset-Ordner liefern.

use std::fmt;

use crate::function::{
    BackupSpec, Freigabe, FunctionDef, Precondition, Provenance, Risk, Step, VehicleMatch,
};

/// Was am Text nicht stimmt — mit Datei und Zeile, damit es auffindbar ist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InhaltsFehler {
    pub datei: String,
    pub zeile: usize,
    pub art: Art,
}

/// Die Art des Fehlers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Art {
    /// Ein Schlüssel, den das Schema nicht kennt (Tippfehler?).
    UnbekannterSchluessel { schluessel: String },
    /// Ein Pflichtfeld fehlt.
    Fehlt { schluessel: &'static str },
    /// Ein Wert lässt sich nicht lesen.
    WertUngueltig { schluessel: String, wert: String },
    /// Einrückung passt nicht zur erwarteten Ebene.
    Einrueckung { erwartet: usize, gefunden: usize },
    /// Eine Zeile, die kein `schlüssel: wert` ist.
    KeinSchluesselWert { zeile: String },
    /// Ein Block wird gebraucht, ist aber leer.
    LeererBlock { schluessel: String },
    /// Ein Abschnitt fehlt ganz.
    AbschnittFehlt { abschnitt: &'static str },
}

impl fmt::Display for InhaltsFehler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}: ", self.datei, self.zeile)?;
        match &self.art {
            Art::UnbekannterSchluessel { schluessel } => {
                write!(f, "unbekannter Schlüssel „{schluessel}\"")
            }
            Art::Fehlt { schluessel } => write!(f, "Pflichtfeld „{schluessel}\" fehlt"),
            Art::WertUngueltig { schluessel, wert } => {
                write!(f, "Wert „{wert}\" für „{schluessel}\" ist ungültig")
            }
            Art::Einrueckung { erwartet, gefunden } => write!(
                f,
                "Einrückung {gefunden} passt nicht (erwartet {erwartet} Leerzeichen)"
            ),
            Art::KeinSchluesselWert { zeile } => {
                write!(f, "kein „schlüssel: wert\" — {zeile}")
            }
            Art::LeererBlock { schluessel } => write!(f, "Block „{schluessel}\" ist leer"),
            Art::AbschnittFehlt { abschnitt } => write!(f, "Abschnitt „{abschnitt}\" fehlt"),
        }
    }
}

impl std::error::Error for InhaltsFehler {}

fn fehler(datei: &str, zeile: usize, art: Art) -> InhaltsFehler {
    InhaltsFehler {
        datei: datei.to_string(),
        zeile,
        art,
    }
}

/// Liest eine Funktion aus Text.
pub fn laden(datei: &str, text: &str) -> std::result::Result<FunctionDef, InhaltsFehler> {
    let mut def = FunctionDef {
        id: String::new(),
        titel: String::new(),
        risk: Risk::R0,
        match_rule: VehicleMatch::default(),
        preconditions: Vec::new(),
        steps: Vec::new(),
        backup: None,
        provenance: Provenance {
            quelle: String::new(),
            freigabe: Freigabe::Unbelegt,
            evidence_hash: String::new(),
        },
    };

    // Abschnitte, in denen wir uns gerade befinden.
    let mut abschnitt = Abschnitt::Kopf;
    let mut risk_gesetzt = false;
    let mut schritt_offen: Option<usize> = None;
    let mut bordspannung: Option<(u32, u32)> = None;
    let mut match_gesehen = false;
    let mut provenance_gesehen = false;
    let mut preconditions_gesehen = false;

    for (index, roh) in text.lines().enumerate() {
        let zeile = index + 1;
        let ohne_kommentar = roh.trim_start();
        if ohne_kommentar.is_empty() || ohne_kommentar.starts_with('#') {
            continue;
        }
        if roh.contains('\t') {
            return Err(fehler(
                datei,
                zeile,
                Art::Einrueckung {
                    erwartet: 2,
                    gefunden: 0,
                },
            ));
        }
        let einzug = roh.len() - roh.trim_start().len();
        let inhalt = roh.trim();

        // Listenpunkt?
        if let Some(rest) = inhalt.strip_prefix("- ") {
            let rest = rest.trim();
            match abschnitt {
                Abschnitt::Preconditions => {
                    if einzug != 2 {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::Einrueckung {
                                erwartet: 2,
                                gefunden: einzug,
                            },
                        ));
                    }
                    if rest == "klemme15" {
                        def.preconditions.push(Precondition::Klemme15);
                    } else if rest == "motor_aus" {
                        def.preconditions.push(Precondition::MotorAus);
                    } else if rest.ends_with(':') {
                        let schluessel = rest.trim_end_matches(':');
                        if schluessel != "bordspannung" {
                            return Err(fehler(
                                datei,
                                zeile,
                                Art::UnbekannterSchluessel {
                                    schluessel: schluessel.to_string(),
                                },
                            ));
                        }
                        bordspannung = Some((0, 0));
                    } else {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::WertUngueltig {
                                schluessel: "preconditions".to_string(),
                                wert: rest.to_string(),
                            },
                        ));
                    }
                    continue;
                }
                Abschnitt::MatchSteuergeraete => {
                    let wert = zahl_u32(rest).ok_or_else(|| {
                        fehler(
                            datei,
                            zeile,
                            Art::WertUngueltig {
                                schluessel: "steuergeraete".to_string(),
                                wert: rest.to_string(),
                            },
                        )
                    })?;
                    def.match_rule.steuergeraete.push(wert);
                    continue;
                }
                Abschnitt::BackupDids => {
                    let wert = zahl_u16(rest).ok_or_else(|| {
                        fehler(
                            datei,
                            zeile,
                            Art::WertUngueltig {
                                schluessel: "dids".to_string(),
                                wert: rest.to_string(),
                            },
                        )
                    })?;
                    def.backup
                        .get_or_insert_with(BackupSpec::default)
                        .dids
                        .push(wert);
                    continue;
                }
                Abschnitt::Steps | Abschnitt::Recovery => {
                    // Ein Listenpunkt auf Ebene 2 beginnt immer einen neuen
                    // Schritt — auch dann, wenn eben noch ein Notausstieg lief.
                    let im_recovery = abschnitt == Abschnitt::Recovery && einzug == 6;
                    if !im_recovery {
                        abschnitt = Abschnitt::Steps;
                    }
                    let erwarteter_einzug = if im_recovery { 6 } else { 2 };
                    if einzug != erwarteter_einzug {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::Einrueckung {
                                erwartet: erwarteter_einzug,
                                gefunden: einzug,
                            },
                        ));
                    }
                    // Neuer Schritt beginnt mit `- anfrage: …`
                    let (schluessel, wert) = trenne(rest).ok_or_else(|| {
                        fehler(
                            datei,
                            zeile,
                            Art::KeinSchluesselWert {
                                zeile: rest.to_string(),
                            },
                        )
                    })?;
                    if schluessel != "anfrage" {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::UnbekannterSchluessel {
                                schluessel: schluessel.to_string(),
                            },
                        ));
                    }
                    let bytes = hex_bytes(wert).ok_or_else(|| {
                        fehler(
                            datei,
                            zeile,
                            Art::WertUngueltig {
                                schluessel: "anfrage".to_string(),
                                wert: wert.to_string(),
                            },
                        )
                    })?;
                    let step = Step {
                        anfrage: bytes,
                        erwartet: Vec::new(),
                        timeout_ms: 0,
                        recovery: Vec::new(),
                    };
                    if im_recovery {
                        let eltern = schritt_offen.ok_or_else(|| {
                            fehler(datei, zeile, Art::AbschnittFehlt { abschnitt: "steps" })
                        })?;
                        def.steps[eltern].recovery.push(step);
                    } else {
                        def.steps.push(step);
                        schritt_offen = Some(def.steps.len() - 1);
                    }
                    continue;
                }
                _ => {
                    return Err(fehler(
                        datei,
                        zeile,
                        Art::UnbekannterSchluessel {
                            schluessel: inhalt.to_string(),
                        },
                    ));
                }
            }
        }

        let (schluessel, wert) = trenne(inhalt).ok_or_else(|| {
            fehler(
                datei,
                zeile,
                Art::KeinSchluesselWert {
                    zeile: inhalt.to_string(),
                },
            )
        })?;

        // Ein Schlüssel ohne Wert eröffnet einen Block (bei den Abschnitten oben)
        // oder ist ein Fehler.
        match (einzug, &abschnitt) {
            (0, _) => {
                // Neue Top-Level-Anweisung: vorherigen Abschnitt abschließen.
                if let Some((min, max)) = bordspannung.take() {
                    def.preconditions
                        .push(Precondition::Bordspannung { min_mv: min, max_mv: max });
                }
                match schluessel {
                    "id" => {
                        def.id = wert.to_string();
                        abschnitt = Abschnitt::Kopf;
                    }
                    "titel" => {
                        def.titel = wert.to_string();
                        abschnitt = Abschnitt::Kopf;
                    }
                    "risk" => {
                        def.risk = Risk::aus_text(wert).ok_or_else(|| {
                            fehler(
                                datei,
                                zeile,
                                Art::WertUngueltig {
                                    schluessel: "risk".to_string(),
                                    wert: wert.to_string(),
                                },
                            )
                        })?;
                        risk_gesetzt = true;
                    }
                    "match" => {
                        if !wert.is_empty() {
                            return Err(fehler(datei, zeile, Art::LeererBlock {
                                schluessel: "match".to_string(),
                            }));
                        }
                        match_gesehen = true;
                        abschnitt = Abschnitt::Match;
                    }
                    "preconditions" => {
                        if !wert.is_empty() {
                            return Err(fehler(datei, zeile, Art::LeererBlock {
                                schluessel: "preconditions".to_string(),
                            }));
                        }
                        preconditions_gesehen = true;
                        abschnitt = Abschnitt::Preconditions;
                    }
                    "backup" => {
                        if !wert.is_empty() {
                            return Err(fehler(datei, zeile, Art::LeererBlock {
                                schluessel: "backup".to_string(),
                            }));
                        }
                        def.backup = Some(BackupSpec::default());
                        abschnitt = Abschnitt::Backup;
                    }
                    "steps" => {
                        if !wert.is_empty() {
                            return Err(fehler(datei, zeile, Art::LeererBlock {
                                schluessel: "steps".to_string(),
                            }));
                        }
                        abschnitt = Abschnitt::Steps;
                    }
                    "provenance" => {
                        if !wert.is_empty() {
                            return Err(fehler(datei, zeile, Art::LeererBlock {
                                schluessel: "provenance".to_string(),
                            }));
                        }
                        provenance_gesehen = true;
                        abschnitt = Abschnitt::Provenance;
                    }
                    other => {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::UnbekannterSchluessel {
                                schluessel: other.to_string(),
                            },
                        ));
                    }
                }
            }
            (2, Abschnitt::Match) => match schluessel {
                "vin_praefix" => def.match_rule.vin_praefix = Some(wert.to_string()),
                "hsn" => def.match_rule.hsn = Some(wert.to_string()),
                "tsn" => def.match_rule.tsn = Some(wert.to_string()),
                "steuergeraete" => {
                    if !wert.is_empty() {
                        return Err(fehler(datei, zeile, Art::LeererBlock {
                            schluessel: "steuergeraete".to_string(),
                        }));
                    }
                    abschnitt = Abschnitt::MatchSteuergeraete;
                }
                other => {
                    return Err(fehler(
                        datei,
                        zeile,
                        Art::UnbekannterSchluessel {
                            schluessel: other.to_string(),
                        },
                    ));
                }
            },
            (2, Abschnitt::Backup) => match schluessel {
                "dids" => {
                    if !wert.is_empty() {
                        return Err(fehler(datei, zeile, Art::LeererBlock {
                            schluessel: "dids".to_string(),
                        }));
                    }
                    abschnitt = Abschnitt::BackupDids;
                }
                other => {
                    return Err(fehler(
                        datei,
                        zeile,
                        Art::UnbekannterSchluessel {
                            schluessel: other.to_string(),
                        },
                    ));
                }
            },
            (2, Abschnitt::Provenance) => match schluessel {
                "quelle" => def.provenance.quelle = wert.to_string(),
                "freigabe" => {
                    def.provenance.freigabe = Freigabe::aus_text(wert).ok_or_else(|| {
                        fehler(
                            datei,
                            zeile,
                            Art::WertUngueltig {
                                schluessel: "freigabe".to_string(),
                                wert: wert.to_string(),
                            },
                        )
                    })?
                }
                "evidence_hash" => def.provenance.evidence_hash = wert.to_string(),
                other => {
                    return Err(fehler(
                        datei,
                        zeile,
                        Art::UnbekannterSchluessel {
                            schluessel: other.to_string(),
                        },
                    ));
                }
            },
            (6, Abschnitt::Preconditions) => match schluessel {
                "min_mv" => {
                    let min = zahl_u32(wert).ok_or_else(|| {
                        fehler(datei, zeile, Art::WertUngueltig {
                            schluessel: "min_mv".to_string(),
                            wert: wert.to_string(),
                        })
                    })?;
                    let max = bordspannung.map(|b| b.1).unwrap_or(0);
                    bordspannung = Some((min, max));
                }
                "max_mv" => {
                    let max = zahl_u32(wert).ok_or_else(|| {
                        fehler(datei, zeile, Art::WertUngueltig {
                            schluessel: "max_mv".to_string(),
                            wert: wert.to_string(),
                        })
                    })?;
                    let min = bordspannung.map(|b| b.0).unwrap_or(0);
                    bordspannung = Some((min, max));
                }
                other => {
                    return Err(fehler(
                        datei,
                        zeile,
                        Art::UnbekannterSchluessel {
                            schluessel: other.to_string(),
                        },
                    ));
                }
            },
            (4, Abschnitt::Steps) => {
                let i = schritt_offen.ok_or_else(|| {
                    fehler(datei, zeile, Art::AbschnittFehlt { abschnitt: "steps" })
                })?;
                match schluessel {
                    "erwartet" => {
                        def.steps[i].erwartet = hex_bytes(wert).ok_or_else(|| {
                            fehler(datei, zeile, Art::WertUngueltig {
                                schluessel: "erwartet".to_string(),
                                wert: wert.to_string(),
                            })
                        })?;
                    }
                    "timeout_ms" => {
                        def.steps[i].timeout_ms = zahl_u32(wert).ok_or_else(|| {
                            fehler(datei, zeile, Art::WertUngueltig {
                                schluessel: "timeout_ms".to_string(),
                                wert: wert.to_string(),
                            })
                        })?;
                    }
                    "recovery" => {
                        if !wert.is_empty() {
                            return Err(fehler(datei, zeile, Art::LeererBlock {
                                schluessel: "recovery".to_string(),
                            }));
                        }
                        abschnitt = Abschnitt::Recovery;
                    }
                    other => {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::UnbekannterSchluessel {
                                schluessel: other.to_string(),
                            },
                        ));
                    }
                }
            }
            (8, Abschnitt::Recovery) => {
                let eltern = schritt_offen.ok_or_else(|| {
                    fehler(datei, zeile, Art::AbschnittFehlt { abschnitt: "steps" })
                })?;
                let i = def.steps[eltern].recovery.len().checked_sub(1).ok_or_else(|| {
                    fehler(datei, zeile, Art::AbschnittFehlt { abschnitt: "recovery" })
                })?;
                match schluessel {
                    "erwartet" => {
                        def.steps[eltern].recovery[i].erwartet =
                            hex_bytes(wert).ok_or_else(|| {
                                fehler(datei, zeile, Art::WertUngueltig {
                                    schluessel: "erwartet".to_string(),
                                    wert: wert.to_string(),
                                })
                            })?;
                    }
                    "timeout_ms" => {
                        def.steps[eltern].recovery[i].timeout_ms = zahl_u32(wert).ok_or_else(|| {
                            fehler(datei, zeile, Art::WertUngueltig {
                                schluessel: "timeout_ms".to_string(),
                                wert: wert.to_string(),
                            })
                        })?;
                    }
                    other => {
                        return Err(fehler(
                            datei,
                            zeile,
                            Art::UnbekannterSchluessel {
                                schluessel: other.to_string(),
                            },
                        ));
                    }
                }
            }
            (gefunden, _) => {
                return Err(fehler(
                    datei,
                    zeile,
                    Art::Einrueckung {
                        erwartet: 2,
                        gefunden,
                    },
                ));
            }
        }
    }

    // Offene Blöcke abschließen.
    if let Some((min, max)) = bordspannung.take() {
        def.preconditions
            .push(Precondition::Bordspannung { min_mv: min, max_mv: max });
    }

    // Pflichtfelder prüfen — mit Zeilenangabe 0, weil das Feld über die ganze
    // Datei fehlt und nicht an einer Zeile.
    if def.id.is_empty() {
        return Err(fehler(datei, 0, Art::Fehlt { schluessel: "id" }));
    }
    if def.titel.is_empty() {
        return Err(fehler(datei, 0, Art::Fehlt { schluessel: "titel" }));
    }
    if !risk_gesetzt {
        return Err(fehler(datei, 0, Art::Fehlt { schluessel: "risk" }));
    }
    if !match_gesehen {
        return Err(fehler(
            datei,
            0,
            Art::AbschnittFehlt { abschnitt: "match" },
        ));
    }
    if !preconditions_gesehen && def.preconditions.is_empty() {
        // Vorbedingungen dürfen fehlen, aber nicht stillschweigend: ein
        // fehlender Abschnitt ist ein anderer Fall als „keine Bedingung".
        return Err(fehler(
            datei,
            0,
            Art::AbschnittFehlt {
                abschnitt: "preconditions",
            },
        ));
    }
    if !provenance_gesehen {
        return Err(fehler(
            datei,
            0,
            Art::AbschnittFehlt {
                abschnitt: "provenance",
            },
        ));
    }
    if def.steps.is_empty() {
        return Err(fehler(datei, 0, Art::AbschnittFehlt { abschnitt: "steps" }));
    }

    Ok(def)
}

/// Die Abschnitte, in denen der Leser sich befinden kann.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Abschnitt {
    Kopf,
    Match,
    MatchSteuergeraete,
    Preconditions,
    Backup,
    BackupDids,
    Steps,
    Recovery,
    Provenance,
}

/// Trennt `schlüssel: wert` und entfernt Anführungszeichen um den Wert.
fn trenne(zeile: &str) -> Option<(&str, &str)> {
    let doppelpunkt = zeile.find(':')?;
    let schluessel = zeile[..doppelpunkt].trim();
    if schluessel.is_empty() {
        return None;
    }
    let wert = zeile[doppelpunkt + 1..].trim();
    let wert = if wert.len() >= 2
        && ((wert.starts_with('"') && wert.ends_with('"'))
            || (wert.starts_with('\'') && wert.ends_with('\'')))
    {
        &wert[1..wert.len() - 1]
    } else {
        wert
    };
    Some((schluessel, wert))
}

/// Hex-Bytes aus Text (`"10 03"` oder `"1003"`).
///
/// Bewusst über die SFD-Funktion: sie akzeptiert Trenner, lehnt aber jedes
/// andere Zeichen ab — kein stilles Überspringen.
fn hex_bytes(text: &str) -> Option<Vec<u8>> {
    if text.is_empty() {
        return None;
    }
    crate::sfd::aus_hex(text).ok()
}

/// Zahl in Dezimal oder mit `0x`-Präfix.
fn zahl_u32(text: &str) -> Option<u32> {
    let text = text.trim();
    if let Some(hex) = text.strip_prefix("0x").or_else(|| text.strip_prefix("0X")) {
        u32::from_str_radix(hex, 16).ok()
    } else {
        text.parse::<u32>().ok()
    }
}

fn zahl_u16(text: &str) -> Option<u16> {
    let wert = zahl_u32(text)?;
    u16::try_from(wert).ok()
}

#[cfg(test)]
mod tests;
