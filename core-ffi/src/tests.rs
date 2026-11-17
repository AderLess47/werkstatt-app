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
    let sfd = Sfd::new(Audit::new(), "VIN".to_string());

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
fn sfd_extended_session_wird_gesendet_und_ausgewertet() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x50, 0x03, 0x00, 0x32, 0x01, 0xF4]);

    Sfd::new(Audit::new(), "VIN".to_string())
        .extended_session(Box::new(lb.clone()), 100)
        .unwrap();

    let sent = lb.sent();
    assert_eq!(sent.len(), 1);
    assert_eq!(&sent[0].data[..3], &[0x02, 0x10, 0x03]);
}

#[test]
fn sfd_status_liefert_die_rohe_antwort_mit() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x62, 0x01, 0x74, 0x01]);

    let status = Sfd::new(Audit::new(), "VIN".to_string())
        .status(Box::new(lb), 100)
        .unwrap();

    assert_eq!(status.hex, "01");
    // Roh: die ganze Antwort, nicht nur die Nutzlast.
    assert_eq!(status.roh_hex, "62017401");
}

#[test]
fn sfd_nrc_kommt_als_eigener_fehlerfall() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x7F, 0x31, 0x35]);
    let err = Sfd::new(Audit::new(), "VIN".to_string())
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

/// Anbieter, der den Seed invertiert — im Repository steht bewusst **kein**
/// echter Algorithmus, auch nicht als Testhilfe. Zustand braucht er keinen:
/// dass der richtige Seed ankam, zeigt der Schlüssel auf dem Bus.
struct XorAnbieter;

impl SchluesselAnbieter for XorAnbieter {
    fn schluessel(&self, _ebene: u32, seed: Vec<u8>) -> Vec<u8> {
        seed.iter().map(|b| b ^ 0xFF).collect()
    }
}

/// Anbieter, der keinen Schlüssel liefert (nicht lizenziert, falscher Seed).
struct LeererAnbieter;

impl SchluesselAnbieter for LeererAnbieter {
    fn schluessel(&self, _ebene: u32, _seed: Vec<u8>) -> Vec<u8> {
        Vec::new()
    }
}

#[test]
fn security_access_ueber_die_grenze() {
    let lb = LoopbackTransport::new();
    // Seed der Ebene 1, dann die Bestätigung auf den Schlüssel.
    push_sf(&lb, &[0x67, 0x01, 0xA1, 0xB2]);
    push_sf(&lb, &[0x67, 0x02]);
    let anbieter = XorAnbieter;

    let ergebnis = Sicherheit::new()
        .security_access(Box::new(lb.clone()), 0x01, Box::new(anbieter), 100)
        .unwrap();

    assert_eq!(ergebnis, SicherheitFfi::Entsperrt);
    let sent = lb.sent();
    assert_eq!(sent.len(), 2);
    assert_eq!(&sent[0].data[..3], &[0x02, 0x27, 0x01]);
    // `A1 B2` invertiert, hinter `27 02`.
    assert_eq!(&sent[1].data[..5], &[0x04, 0x27, 0x02, 0x5E, 0x4D]);
}

#[test]
fn security_access_bei_offener_ebene_sendet_keinen_schluessel() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x67, 0x01, 0x00, 0x00]);

    let ergebnis = Sicherheit::new()
        .security_access(Box::new(lb.clone()), 0x01, Box::new(LeererAnbieter), 100)
        .unwrap();

    assert_eq!(ergebnis, SicherheitFfi::BereitsOffen);
    // Nur die Seed-Anfrage ging hinaus — der Anbieter wurde nicht gebraucht.
    assert_eq!(lb.sent().len(), 1);
}

#[test]
fn security_access_ohne_schluessel_meldet_protokollfehler() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x67, 0x01, 0xA1, 0xB2]);

    let err = Sicherheit::new()
        .security_access(Box::new(lb.clone()), 0x01, Box::new(LeererAnbieter), 100)
        .unwrap_err();

    match err {
        VciError::Protocol { detail } => assert!(detail.contains("Schlüssel"), "{detail}"),
        other => panic!("erwartet: Protocol, war: {other}"),
    }
    assert_eq!(lb.sent().len(), 1, "kein Schlüsselrahmen");
}

#[test]
fn security_access_nrc_kommt_als_eigener_fehlerfall() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x67, 0x01, 0xA1, 0xB2]);
    push_sf(&lb, &[0x7F, 0x27, 0x35]);
    let anbieter = XorAnbieter;

    let err = Sicherheit::new()
        .security_access(Box::new(lb), 0x01, Box::new(anbieter), 100)
        .unwrap_err();

    assert_eq!(
        err,
        VciError::Nrc {
            dienst: 0x27,
            code: 0x35
        }
    );
    assert!(err.to_string().contains("invalidKey"), "{err}");
}

#[test]
fn sfd_lehnt_ungueltigen_hex_text_ab() {
    let lb = LoopbackTransport::new();
    let err = Sfd::new(Audit::new(), "VIN".to_string())
        .entsperren(Box::new(lb), "A1G2".to_string(), 100)
        .unwrap_err();
    assert!(matches!(err, VciError::Sfd { .. }), "{err}");
}

/// Kleine Definition für die Grenztests: eine Sitzung, kein Backup,
/// keine Spannungsbedingung (der Loopback kann keine messen).
fn yaml(risk: &str, freigabe: &str, evidence: &str) -> String {
    format!(
            "id: grenztest\ntitel: Grenztest\nrisk: {risk}\nmatch:\n  vin_praefix: WVW\npreconditions:\n  - klemme15\nsteps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 1000\nprovenance:\n  quelle: Test\n  freigabe: {freigabe}\n  evidence_hash: \"{evidence}\"\n"
        )
}

#[test]
fn funktion_laeuft_ueber_die_grenze_und_protokolliert() {
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x50, 0x03, 0x00, 0x32]);
    let funktion = Funktion::aus_yaml("t.yaml".to_string(), yaml("R1", "geprüft", "a1b2")).unwrap();
    let audit = Audit::new();

    let ergebnis = funktion
        .ausfuehren(
            Box::new(lb.clone()),
            FunktionsKontextFfi {
                vin: "WVWZZZ1JZXW000001".to_string(),
                klemme15: true,
                motor_aus: true,
                bestaetigung: false,
            },
            audit.clone(),
            1_000,
        )
        .unwrap();

    assert_eq!(ergebnis.funktion, "grenztest");
    assert_eq!(ergebnis.schritte, 1);
    assert_eq!(ergebnis.audit_laufnummern, vec![1]);
    assert_eq!(audit.len(), 1);
    assert!(audit.pruefen().is_ok());
    assert_eq!(lb.sent()[0].data[..3], [0x02, 0x10, 0x03]);
    let json = audit.als_json();
    assert!(json.contains("session 10 03"), "{json}");
}

#[test]
fn funktion_ab_r2_ohne_bestaetigung_wird_abgelehnt() {
    let lb = LoopbackTransport::new();
    let funktion = Funktion::aus_yaml("t.yaml".to_string(), yaml("R2", "geprüft", "a1b2")).unwrap();
    let audit = Audit::new();

    let err = funktion
        .ausfuehren(
            Box::new(lb.clone()),
            FunktionsKontextFfi {
                vin: "VIN".to_string(),
                klemme15: true,
                motor_aus: true,
                bestaetigung: false,
            },
            audit.clone(),
            5,
        )
        .unwrap_err();

    assert!(matches!(err, VciError::Protocol { .. }), "{err}");
    assert!(lb.sent().is_empty(), "es darf kein Rahmen rausgehen");
    assert_eq!(audit.len(), 1, "die Ablehnung wird protokolliert");
}

#[test]
fn unbelegter_entwurf_ab_r2_wird_schon_beim_laden_abgelehnt() {
    let funktion = Funktion::aus_yaml("t.yaml".to_string(), yaml("R2", "unbelegt", "")).unwrap();
    let fehler = funktion.pruefe_freigabe().unwrap_err();
    assert!(matches!(fehler, VciError::Protocol { .. }), "{fehler}");
}

#[test]
fn audit_kette_ueber_die_grenze_prueft_und_exportiert() {
    let audit = Audit::new();
    audit
        .anhaengen(
            1,
            "VIN".to_string(),
            0x7E0,
            "session 10 03".to_string(),
            true,
            String::new(),
        )
        .unwrap();
    audit
        .anhaengen(
            2,
            "VIN".to_string(),
            0x7E0,
            "routine 31 01 02 03".to_string(),
            false,
            "Timeout".to_string(),
        )
        .unwrap();

    assert_eq!(audit.len(), 2);
    assert!(audit.pruefen().is_ok());
    let eintraege = audit.eintraege();
    assert_eq!(eintraege[1].ergebnis, "Fehler: Timeout");
    assert_eq!(eintraege[1].vorheriger_hash, eintraege[0].hash);
    assert_eq!(audit.letzter_hash(), eintraege[1].hash);
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
    Sfd::new(Audit::new(), "VIN".to_string())
        .entsperren_mit_bytes(Box::new(lb.clone()), token.clone(), 100)
        .unwrap();

    let sent = lb.sent();
    assert_eq!(sent[0].data[0] & 0xF0, 0x10, "First Frame");
    let angekuendigt = (((sent[0].data[0] as usize) & 0x0F) << 8) | sent[0].data[1] as usize;
    assert_eq!(angekuendigt, 4 + token.len());
    assert!(sent.len() > 40, "300 Byte brauchen viele Rahmen");
}

#[test]
fn sfd_entsperren_und_sperren_schreiben_in_die_kette() {
    let audit = Audit::new();
    let sfd = Sfd::new(audit.clone(), "WVWZZZ1JZXW000001".to_string());

    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x71, 0x01, 0xC0, 0x08]);
    sfd.entsperren(Box::new(lb), "DEAD".to_string(), 100)
        .unwrap();

    let eintraege = audit.eintraege();
    assert_eq!(eintraege.len(), 1);
    assert_eq!(eintraege[0].dienst, "SFD entsperren 31 01 C0 08");
    assert_eq!(eintraege[0].ergebnis, "Erfolg");
    assert_eq!(eintraege[0].vin, "WVWZZZ1JZXW000001");
    assert!(audit.pruefen().is_ok());
    assert!(
        !audit.als_json().contains("DEAD"),
        "der Token gehört nicht in den Nachweis"
    );

    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x71, 0x01, 0xC0, 0x09]);
    sfd.sperren(Box::new(lb), 100).unwrap();

    let eintraege = audit.eintraege();
    assert_eq!(eintraege.len(), 2);
    assert_eq!(eintraege[1].dienst, "SFD sperren 31 01 C0 09");
    assert_eq!(eintraege[1].vorheriger_hash, eintraege[0].hash);
    assert!(audit.pruefen().is_ok());
}

#[test]
fn sfd_ablehnung_steht_als_fehler_in_der_kette() {
    let audit = Audit::new();
    let lb = LoopbackTransport::new();
    push_sf(&lb, &[0x7F, 0x31, 0x35]);

    let err = Sfd::new(audit.clone(), "VIN".to_string())
        .entsperren(Box::new(lb), "DEAD".to_string(), 100)
        .unwrap_err();
    assert!(matches!(err, VciError::Nrc { .. }), "{err}");

    let eintraege = audit.eintraege();
    assert_eq!(eintraege.len(), 1, "auch die Ablehnung wird protokolliert");
    assert!(
        eintraege[0].ergebnis.starts_with("Fehler:"),
        "{eintraege:?}"
    );
}

#[test]
fn vorbedingungen_und_schrittliste_kommen_als_daten_an() {
    // Dieselbe Form wie im Katalog: Spannungsgrenzen als min_mv/max_mv.
    let text = "id: spannung\ntitel: Spannung\nrisk: R1\nmatch:\n  vin_praefix: WVW\npreconditions:\n  - klemme15\n  - motor_aus\n  - bordspannung:\n      min_mv: 11800\n      max_mv: 14400\nsteps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 2000\nprovenance:\n  quelle: Test\n  freigabe: geprüft\n  evidence_hash: \"a1b2\"\n";
    let funktion = Funktion::aus_yaml("spannung.yaml".to_string(), text.to_string()).unwrap();

    let bedingungen = funktion.vorbedingungen();
    assert_eq!(bedingungen.len(), 3);
    assert_eq!(bedingungen[0].art, "klemme15");
    assert_eq!(bedingungen[1].art, "motor_aus");
    assert_eq!(bedingungen[2].art, "bordspannung");
    assert_eq!(
        (bedingungen[2].min_mv, bedingungen[2].max_mv),
        (11800, 14400)
    );

    let schritte = funktion.schrittliste();
    assert_eq!(schritte.len(), 1);
    assert_eq!(schritte[0].nummer, 1, "Schritte zählen ab 1");
    assert_eq!(schritte[0].anfrage_hex, "10 03");
    assert_eq!(schritte[0].erwartet_hex, "50 03");
    assert_eq!(schritte[0].timeout_ms, 2000);
}
