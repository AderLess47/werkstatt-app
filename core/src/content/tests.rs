use super::*;

const OELSERVICE: &str = include_str!("../../../content/funktionen/oelservice-rueckstellung.yaml");
const EPB: &str = include_str!("../../../content/funktionen/epb-wartungsmodus.yaml");
const DPF: &str = include_str!("../../../content/funktionen/dpf-regeneration.yaml");
const BATTERIE: &str = include_str!("../../../content/funktionen/batterie-anlernen.yaml");
const LENKWINKEL: &str = include_str!("../../../content/funktionen/lenkwinkel-kalibrierung.yaml");

fn alle() -> [(&'static str, &'static str); 5] {
    [
        ("oelservice-rueckstellung.yaml", OELSERVICE),
        ("epb-wartungsmodus.yaml", EPB),
        ("dpf-regeneration.yaml", DPF),
        ("batterie-anlernen.yaml", BATTERIE),
        ("lenkwinkel-kalibrierung.yaml", LENKWINKEL),
    ]
}

#[test]
fn alle_fuenf_inhalte_laden_und_sind_strukturell_gueltig() {
    for (name, text) in alle() {
        let def = laden(name, text).unwrap_or_else(|e| panic!("{e}"));
        def.pruefe_schema()
            .unwrap_or_else(|f| panic!("{name}: {f:?}"));
    }
}

#[test]
fn oelservice_ist_r1_und_ohne_bestaetigung_lauffaehig() {
    let def = laden("oelservice", OELSERVICE).unwrap();

    assert_eq!(def.id, "oelservice-rueckstellung");
    assert_eq!(def.risk, Risk::R1);
    assert!(!def.risk.braucht_bestaetigung());
    assert!(def.pruefe_freigabe().is_ok());
    // Klemme 15 und Spannungsfenster sind gesetzt.
    assert!(def
        .preconditions
        .iter()
        .any(|p| matches!(p, Precondition::Klemme15)));
    assert!(def
        .preconditions
        .iter()
        .any(|p| matches!(p, Precondition::Bordspannung { .. })));
}

#[test]
fn die_vier_entwuerfe_ab_r2_werden_von_der_freigabe_angehalten() {
    // Genau der Zweck der Freigabestufe: die Katalogentwürfe sind
    // strukturell in Ordnung, aber ohne Nachweis nicht lauffähig.
    for (name, text) in alle() {
        let def = laden(name, text).unwrap();
        match def.risk {
            Risk::R2 | Risk::R3 | Risk::R4 => {
                assert!(
                    def.pruefe_freigabe().is_err(),
                    "{name} läuft als unbelegter Entwurf durch"
                );
            }
            _ => assert!(def.pruefe_freigabe().is_ok(), "{name}"),
        }
    }
}

#[test]
fn schritte_und_hex_kommen_richtig_an() {
    let def = laden("oelservice", OELSERVICE).unwrap();

    assert_eq!(def.steps[0].anfrage, vec![0x10, 0x03]);
    assert_eq!(def.steps[0].erwartet, vec![0x50, 0x03]);
    assert_eq!(def.steps[0].timeout_ms, 2_000);
    assert!(!def.steps[0].recovery.is_empty(), "Notausstieg fehlt");
}

#[test]
fn unbekannter_schluessel_wird_abgelehnt() {
    let text = "id: x\ntitel: y\nrisk: R0\nmatch:\n  vin_praefix: WVW\nvorbedingungen:\nsteps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 100\nprovenance:\n  freigabe: freigegeben\n  evidence_hash: \"a1b2\"\n  quelle: t\n";
    let err = laden("x.yaml", text).unwrap_err();

    assert_eq!(
        err.art,
        Art::UnbekannterSchluessel {
            schluessel: "vorbedingungen".to_string()
        }
    );
    assert_eq!(err.zeile, 6);
}

#[test]
fn fehlendes_provenance_wird_gemeldet() {
    let text = "id: x\ntitel: y\nrisk: R0\nmatch:\n  vin_praefix: WVW\npreconditions:\n  - klemme15\nsteps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 100\n";
    let err = laden("x.yaml", text).unwrap_err();

    assert_eq!(
        err.art,
        Art::AbschnittFehlt {
            abschnitt: "provenance"
        }
    );
}

#[test]
fn kaputter_hex_wert_wird_abgelehnt() {
    let text = "id: x\ntitel: y\nrisk: R0\nmatch:\n  vin_praefix: WVW\npreconditions:\n  - klemme15\nsteps:\n  - anfrage: \"10 0Z\"\n    erwartet: \"50 03\"\n    timeout_ms: 100\nprovenance:\n  freigabe: unbelegt\n";
    let err = laden("x.yaml", text).unwrap_err();

    assert_eq!(err.zeile, 9);
    assert!(matches!(err.art, Art::WertUngueltig { .. }));
}

#[test]
fn tabulator_wird_abgelehnt() {
    let text = "id: x\n\ttitel: y\n";
    let err = laden("x.yaml", text).unwrap_err();
    assert!(matches!(err.art, Art::Einrueckung { .. }));
}

#[test]
fn ohne_wert_bei_einem_pflichtfeld_bleibt_das_feld_leer_und_faellt_auf() {
    let text = "id:\ntitel: y\nrisk: R0\nmatch:\n  vin_praefix: WVW\npreconditions:\n  - klemme15\nsteps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 100\nprovenance:\n  freigabe: unbelegt\n";
    let err = laden("x.yaml", text).unwrap_err();

    assert_eq!(err.art, Art::Fehlt { schluessel: "id" });
}

#[test]
fn kommentare_und_leerzeilen_stoeren_nicht() {
    let text = "# Kopf\n\nid: x\ntitel: y\nrisk: R0\nmatch:\n  vin_praefix: WVW\npreconditions:\n  - klemme15\nsteps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 100\nprovenance:\n  freigabe: unbelegt\n";
    assert!(laden("x.yaml", text).is_ok());
}

#[test]
fn zahlen_lesen_dezimal_und_hex() {
    assert_eq!(zahl_u32("2000"), Some(2_000));
    assert_eq!(zahl_u32("0x7E0"), Some(0x7E0));
    assert_eq!(zahl_u32("0xZZ"), None);
    assert_eq!(zahl_u16("0xF190"), Some(0xF190));
    assert_eq!(zahl_u16("70000"), None);
}
