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

#[test]
fn sfd_token_mit_300_byte_geht_ueber_die_grenze() {
    let lb = LoopbackTransport::new();
    lb.push(CanFrameFfi {
        id: 0x7E8,
        data: vec![0x30, 0x00, 0x00],
        dlc: 8,
    });
    push_sf(&lb, &[0x71, 0x01, 0xC0, 0x08]);

    Sfd::new(Audit::new(), "VIN".to_string())
        .entsperren_mit_bytes(Box::new(lb.clone()), vec![0x5A; 300], 100)
        .unwrap();

    let sent = lb.sent();
    assert_eq!(sent[0].data[0] & 0xF0, 0x10);
    let angekuendigt =
        ((sent[0].data[0] as usize & 0x0F) << 8) | sent[0].data[1] as usize;
    assert_eq!(angekuendigt, 4 + 300);
    assert!(sent.len() > 40, "viele Rahmen, waren: {}", sent.len());
}

#[test]
fn sfd_entsperren_und_sperren_schreiben_in_die_kette() {
    let lb = LoopbackTransport::new();
    let audit = Audit::new();
    let sfd = Sfd::new(audit.clone(), "WVWZZZ1JZXW000001".to_string());

    push_sf(&lb, &[0x71, 0x01, 0xC0, 0x08]);
    sfd.entsperren(Box::new(lb.clone()), "DEAD".to_string(), 100)
        .unwrap();
    push_sf(&lb, &[0x71, 0x01, 0xC0, 0x09]);
    sfd.sperren(Box::new(lb.clone()), 100).unwrap();

    assert_eq!(audit.len(), 2);
    let eintraege = audit.eintraege();
    assert_eq!(eintraege[0].dienst, "SFD entsperren 31 01 C0 08");
    assert_eq!(eintraege[0].ergebnis, "Erfolg");
    assert_eq!(eintraege[1].dienst, "SFD sperren 31 01 C0 09");
    assert_eq!(eintraege[0].hash, eintraege[1].vorheriger_hash);
    audit.pruefen().unwrap();
    assert!(
        !audit.als_json().contains("DEAD"),
        "Token darf nicht im Nachweis stehen"
    );
}

#[test]
fn sfd_ablehnung_steht_als_fehler_in_der_kette() {
    let lb = LoopbackTransport::new();
    let audit = Audit::new();
    push_sf(&lb, &[0x7F, 0x31, 0x35]);

    Sfd::new(audit.clone(), "VIN".to_string())
        .entsperren(Box::new(lb), "DEAD".to_string(), 100)
        .unwrap_err();

    assert_eq!(audit.len(), 1);
    assert!(audit.eintraege()[0].ergebnis.contains("invalidKey"));
}
