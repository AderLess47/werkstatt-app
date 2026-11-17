use super::*;
use crate::loopback::Loopback;
use crate::transport::{CanFrame, Capabilities};

/// Ein Loopback, der eine Bordspannung meldet — der Standard-Loopback kann
/// das nicht, und ohne Spannung läuft keine Funktion mit Spannungsbedingung.
struct NetzLoopback {
    inner: Loopback,
    vbat_mv: u32,
}

impl NetzLoopback {
    fn neu(frames: Vec<CanFrame>, vbat_mv: u32) -> Self {
        NetzLoopback {
            inner: Loopback::new(frames),
            vbat_mv,
        }
    }
}

impl Transport for NetzLoopback {
    fn send(&mut self, frame: &CanFrame) -> crate::transport::Result<()> {
        self.inner.send(frame)
    }
    fn recv(&mut self, timeout_ms: u32) -> crate::transport::Result<CanFrame> {
        self.inner.recv(timeout_ms)
    }
    fn vbat_mv(&mut self) -> crate::transport::Result<u32> {
        Ok(self.vbat_mv)
    }
    fn capabilities(&self) -> Capabilities {
        self.inner.capabilities()
    }
}

fn antwort(bytes: &[u8]) -> CanFrame {
    let mut f = vec![bytes.len() as u8];
    f.extend_from_slice(bytes);
    CanFrame::new(crate::isotp::RSP_ID, &f)
}

fn definition(risk: Risk, freigabe: Freigabe) -> FunctionDef {
    FunctionDef {
        id: "oelservice".to_string(),
        titel: "Ölservice zurückstellen".to_string(),
        risk,
        match_rule: VehicleMatch {
            vin_praefix: Some("WVW".to_string()),
            ..VehicleMatch::default()
        },
        preconditions: vec![
            Precondition::Klemme15,
            Precondition::MotorAus,
            Precondition::Bordspannung {
                min_mv: 11_000,
                max_mv: 15_000,
            },
        ],
        steps: vec![
            Step::neu(&[0x10, 0x03], &[0x50, 0x03], 1_000),
            Step::neu(&[0x31, 0x01, 0x02, 0x03], &[0x71, 0x01, 0x02, 0x03], 2_000),
        ],
        backup: None,
        provenance: Provenance {
            quelle: "Test".to_string(),
            freigabe,
            evidence_hash: if freigabe == Freigabe::Unbelegt {
                String::new()
            } else {
                "a1b2c3".to_string()
            },
        },
    }
}

fn zwei_antworten() -> Vec<CanFrame> {
    vec![
        antwort(&[0x50, 0x03, 0x00, 0x32]),
        antwort(&[0x71, 0x01, 0x02, 0x03]),
    ]
}

// --- Risiko und Freigabe ---------------------------------------------

#[test]
fn risiko_wird_gelesen_und_benannt() {
    assert_eq!(Risk::aus_text("R2"), Some(Risk::R2));
    assert_eq!(Risk::aus_text("r2"), None);
    assert_eq!(Risk::aus_text("R9"), None);
    assert_eq!(Risk::R3.als_text(), "R3");
}

#[test]
fn ab_r2_braucht_bestaetigung_und_schreibt() {
    assert!(!Risk::R0.schreibt());
    assert!(!Risk::R0.braucht_bestaetigung());
    assert!(Risk::R1.schreibt());
    assert!(!Risk::R1.braucht_bestaetigung());
    assert!(Risk::R2.braucht_bestaetigung());
    assert!(Risk::R4.braucht_bestaetigung());
}

#[test]
fn vin_praefix_und_hsn_werden_geprueft() {
    let regel = VehicleMatch {
        vin_praefix: Some("WVW".to_string()),
        hsn: Some("0603".to_string()),
        tsn: None,
        steuergeraete: vec![0x7E0],
    };
    assert!(regel.passt("WVWZZZ1JZXW000001", Some("0603"), None));
    assert!(!regel.passt("WAUZZZ8PZ8A000001", Some("0603"), None));
    assert!(!regel.passt("WVWZZZ1JZXW000001", Some("0000"), None));
    assert!(!regel.passt("WVWZZZ1JZXW000001", None, None));
    assert!(!regel.ist_leer());
}

#[test]
fn leere_match_regel_wird_abgelehnt() {
    let mut def = definition(Risk::R0, Freigabe::Freigegeben);
    def.match_rule = VehicleMatch::default();
    assert_eq!(
        def.pruefe_schema(),
        Err(vec![ValidierungsFehler::MatchRegelLeer])
    );
}

// --- Strukturelle Prüfung --------------------------------------------

#[test]
fn gueltige_definition_geht_durch() {
    let def = definition(Risk::R1, Freigabe::Geprueft);
    assert!(def.pruefe_schema().is_ok());
    assert!(def.pruefe_freigabe().is_ok());
}

#[test]
fn id_muss_klein_sein() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.id = "OelService".to_string();
    assert_eq!(
        def.pruefe_schema(),
        Err(vec![ValidierungsFehler::IdNichtKlein {
            id: "OelService".to_string()
        }])
    );
}

#[test]
fn leerer_titel_und_keine_schritte_werden_gemeldet() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.titel = "  ".to_string();
    def.steps.clear();
    let fehler = def.pruefe_schema().unwrap_err();
    assert!(fehler.contains(&ValidierungsFehler::TitelLeer));
    assert!(fehler.contains(&ValidierungsFehler::KeineSchritte));
}

#[test]
fn leerer_erwartungswert_wird_abgelehnt() {
    // Eine leere Erwartung würde jede Antwort durchwinken.
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.steps[0].erwartet.clear();
    assert_eq!(
        def.pruefe_schema(),
        Err(vec![ValidierungsFehler::ErwartungLeer { schritt: 0 }])
    );
}

#[test]
fn timeout_null_wird_abgelehnt() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.steps[1].timeout_ms = 0;
    assert_eq!(
        def.pruefe_schema(),
        Err(vec![ValidierungsFehler::TimeoutNull { schritt: 1 }])
    );
}

#[test]
fn bordspannungsfenster_muss_sinnvoll_sein() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.preconditions = vec![Precondition::Bordspannung {
        min_mv: 15_000,
        max_mv: 11_000,
    }];
    assert_eq!(
        def.pruefe_schema(),
        Err(vec![ValidierungsFehler::BordspannungUngueltig {
            min_mv: 15_000,
            max_mv: 11_000
        }])
    );
}

#[test]
fn recovery_darf_keinen_eigenen_notausstieg_haben() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    let mut inner = Step::neu(&[0x10, 0x01], &[0x50, 0x01], 100);
    inner.recovery = vec![Step::neu(&[0x3E, 0x00], &[0x7E, 0x00], 100)];
    def.steps[0].recovery = vec![inner];
    assert!(def
        .pruefe_schema()
        .unwrap_err()
        .contains(&ValidierungsFehler::RecoveryZuTief { schritt: 0 }));
}

#[test]
fn entwurf_ab_r2_wird_nicht_freigegeben() {
    let def = definition(Risk::R2, Freigabe::Unbelegt);
    assert_eq!(
        def.pruefe_freigabe(),
        Err(vec![ValidierungsFehler::FreigabeFehltFuerRisiko {
            risk: Risk::R2,
            freigabe: Freigabe::Unbelegt
        }])
    );
}

#[test]
fn freigabe_ohne_nachweis_wird_abgelehnt() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.provenance.evidence_hash.clear();
    assert_eq!(
        def.pruefe_freigabe(),
        Err(vec![ValidierungsFehler::EvidenceFehlt {
            freigabe: Freigabe::Geprueft
        }])
    );
}

#[test]
fn nachweis_muss_hex_sein() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.provenance.evidence_hash = "xyz".to_string();
    assert!(def
        .pruefe_freigabe()
        .unwrap_err()
        .iter()
        .any(|f| matches!(f, ValidierungsFehler::EvidenceKeinHex { .. })));
}

// --- Vorbedingungen ---------------------------------------------------

#[test]
fn ohne_klemme15_laeuft_nichts() {
    let def = definition(Risk::R1, Freigabe::Geprueft);
    let mut t = NetzLoopback::neu(zwei_antworten(), 12_000);
    let kontext = Kontext::neu("WVWZZZ1JZXW000001").mit_klemme15(false);

    assert_eq!(
        def.pruefe_vorbedingungen(&mut t, &kontext),
        Err(AusfuehrungsFehler::Vorbedingung {
            grund: "Klemme 15 liegt nicht an".to_string()
        })
    );
}

#[test]
fn spannung_ausserhalb_des_fensters_haelt_den_ablauf_an() {
    let def = definition(Risk::R1, Freigabe::Geprueft);
    let mut t = NetzLoopback::neu(zwei_antworten(), 10_500);
    let kontext = Kontext::neu("WVWZZZ1JZXW000001");

    match def.pruefe_vorbedingungen(&mut t, &kontext) {
        Err(AusfuehrungsFehler::Vorbedingung { grund }) => {
            assert!(grund.contains("10500"), "{grund}");
        }
        andere => panic!("erwartet Vorbedingungsfehler, gesehen {andere:?}"),
    }
}

#[test]
fn spannung_nicht_messbar_ist_kein_freifahrtschein() {
    let def = definition(Risk::R1, Freigabe::Geprueft);
    // Der nackte Loopback kann keine Spannung messen.
    let mut t = Loopback::new(zwei_antworten());
    let kontext = Kontext::neu("WVWZZZ1JZXW000001");

    match def.pruefe_vorbedingungen(&mut t, &kontext) {
        Err(AusfuehrungsFehler::Vorbedingung { grund }) => {
            assert!(grund.contains("nicht messbar"), "{grund}");
        }
        andere => panic!("erwartet Vorbedingungsfehler, gesehen {andere:?}"),
    }
}

// --- Ablauf -----------------------------------------------------------

#[test]
fn ablauf_schreibt_jeden_schritt_in_die_kette() {
    let def = definition(Risk::R1, Freigabe::Geprueft);
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(zwei_antworten(), 12_000);
    let mut kette = AuditKette::neu();

    let ergebnis = def
        .ausfuehren(
            &client,
            &mut t,
            &Kontext::neu("WVWZZZ1JZXW000001"),
            &mut kette,
            1_000,
        )
        .unwrap();

    assert_eq!(ergebnis.schritte, 2);
    assert_eq!(ergebnis.audit_laufnummern, vec![1, 2]);
    assert!(ergebnis.ist_vollstaendig_protokolliert());
    assert_eq!(kette.len(), 2);
    assert!(kette.pruefen().is_ok());
    assert_eq!(kette.eintraege()[0].dienst, "session 10 03");
    assert_eq!(kette.eintraege()[1].dienst, "routine 31 01 02 03");
    assert_eq!(kette.eintraege()[0].vin, "WVWZZZ1JZXW000001");
}

#[test]
fn falsche_antwort_bricht_ab_und_wird_protokolliert() {
    let def = definition(Risk::R1, Freigabe::Geprueft);
    let client = UdsClient::new();
    // Auf die Sitzungsanfrage kommt eine Kennungsantwort.
    let mut t = NetzLoopback::neu(vec![antwort(&[0x62, 0xF1, 0x90, 0x00])], 12_000);
    let mut kette = AuditKette::neu();

    let fehler = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 5)
        .unwrap_err();

    assert!(matches!(
        fehler,
        AusfuehrungsFehler::AntwortPasstNicht { schritt: 0, .. }
    ));
    assert_eq!(kette.len(), 1);
    assert!(!kette.eintraege()[0].ergebnis.ist_erfolg());
    assert!(kette.pruefen().is_ok());
}

#[test]
fn bestaetigung_fehlt_ab_r2_und_wird_protokolliert() {
    let def = definition(Risk::R2, Freigabe::Geprueft);
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(zwei_antworten(), 12_000);
    let mut kette = AuditKette::neu();

    let fehler = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 7)
        .unwrap_err();

    assert_eq!(
        fehler,
        AusfuehrungsFehler::BestaetigungFehlt { risk: Risk::R2 }
    );
    // Vor dem ersten Rahmen abgelehnt: nichts gesendet.
    assert!(t.inner.sent().is_empty());
    assert_eq!(kette.len(), 1);
    assert!(kette.eintraege()[0].dienst.contains("abgelehnt"));
}

#[test]
fn bestaetigung_laesst_r2_laufen() {
    let def = definition(Risk::R2, Freigabe::Geprueft);
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(zwei_antworten(), 12_000);
    let mut kette = AuditKette::neu();
    let kontext = Kontext::neu("VIN").mit_bestaetigung(true);

    let ergebnis = def
        .ausfuehren(&client, &mut t, &kontext, &mut kette, 9)
        .unwrap();

    assert_eq!(ergebnis.schritte, 2);
    assert_eq!(kette.len(), 2);
}

#[test]
fn unbelegter_entwurf_ab_r2_laeuft_auch_mit_bestaetigung_nicht() {
    let def = definition(Risk::R2, Freigabe::Unbelegt);
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(zwei_antworten(), 12_000);
    let mut kette = AuditKette::neu();
    let kontext = Kontext::neu("VIN").mit_bestaetigung(true);

    let fehler = def
        .ausfuehren(&client, &mut t, &kontext, &mut kette, 9)
        .unwrap_err();

    assert!(matches!(fehler, AusfuehrungsFehler::Validierung(_)));
    assert!(kette.is_empty());
}

#[test]
fn notausstieg_wird_gesendet_und_protokolliert() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.steps[0].recovery = vec![Step::neu(&[0x10, 0x01], &[0x50, 0x01], 500)];
    let client = UdsClient::new();
    // Sitzung scheitert, der Notausstieg wechselt zurück und gelingt.
    let mut t = NetzLoopback::neu(
        vec![antwort(&[0x7F, 0x10, 0x22]), antwort(&[0x50, 0x01])],
        12_000,
    );
    let mut kette = AuditKette::neu();

    let fehler = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 11)
        .unwrap_err();

    assert!(matches!(fehler, AusfuehrungsFehler::Uds(_)));
    assert_eq!(kette.len(), 2);
    assert_eq!(kette.eintraege()[0].dienst, "session 10 03");
    assert_eq!(kette.eintraege()[1].dienst, "recovery session 10 01");
    assert!(kette.eintraege()[1].ergebnis.ist_erfolg());
}

#[test]
fn gescheiterter_notausstieg_wird_gemeldet() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.steps[0].recovery = vec![Step::neu(&[0x10, 0x01], &[0x50, 0x01], 500)];
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(vec![antwort(&[0x7F, 0x10, 0x22])], 12_000);
    let mut kette = AuditKette::neu();

    let fehler = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 13)
        .unwrap_err();

    assert!(matches!(
        fehler,
        AusfuehrungsFehler::RecoveryFehlgeschlagen { schritt: 0, .. }
    ));
    assert_eq!(kette.len(), 2);
    assert!(!kette.eintraege()[1].ergebnis.ist_erfolg());
}

#[test]
fn backup_wird_vor_dem_schreiben_gelesen() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.backup = Some(BackupSpec { dids: vec![0xF190] });
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(
        vec![
            antwort(&[0x62, 0xF1, 0x90]),
            antwort(&[0x50, 0x03]),
            antwort(&[0x71, 0x01, 0x02, 0x03]),
        ],
        12_000,
    );
    let mut kette = AuditKette::neu();

    let ergebnis = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 15)
        .unwrap();

    assert_eq!(ergebnis.backup.len(), 1);
    assert_eq!(ergebnis.backup[0].0, 0xF190);
    // Die erste gesendete Anfrage ist das Lesen der Kennung — vor dem Schreiben.
    assert_eq!(&t.inner.sent()[0].data[..4], &[0x03, 0x22, 0xF1, 0x90]);
}

#[test]
fn backup_fehler_verhindert_das_schreiben() {
    let mut def = definition(Risk::R1, Freigabe::Geprueft);
    def.backup = Some(BackupSpec { dids: vec![0xF190] });
    let client = UdsClient::new();
    // Keine Antwort auf das Kennungslesen.
    let mut t = NetzLoopback::neu(Vec::new(), 12_000);
    let mut kette = AuditKette::neu();

    let fehler = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 17)
        .unwrap_err();

    assert!(matches!(
        fehler,
        AusfuehrungsFehler::BackupFehlgeschlagen { did: 0xF190, .. }
    ));
    assert!(t.inner.sent().len() == 1, "nur das Backup wurde gesendet");
    assert_eq!(kette.len(), 1);
}

#[test]
fn lesende_funktion_ohne_backup_schreibt_nicht() {
    let mut def = definition(Risk::R0, Freigabe::Freigegeben);
    def.preconditions.clear();
    def.backup = Some(BackupSpec { dids: vec![0xF190] });
    let client = UdsClient::new();
    let mut t = NetzLoopback::neu(zwei_antworten(), 12_000);
    let mut kette = AuditKette::neu();

    let ergebnis = def
        .ausfuehren(&client, &mut t, &Kontext::neu("VIN"), &mut kette, 19)
        .unwrap();

    // R0 liest nur — kein Backup, weil nichts geschrieben wird.
    assert!(ergebnis.backup.is_empty());
    assert_eq!(kette.len(), 2);
}
