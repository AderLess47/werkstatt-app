# Werkstatt-App für Tablet & Smartphone — Planungsdokument

**Version:** 0.1 (Planung) · **Datum:** 14.11.2026 · **Status:** Entscheidungsvorlage
**Ziel:** Native Android- und Apple-App (Smartphone + Tablet) für freie Werkstätten: Diagnose, Service-Rückstellung, ECU-Codierung/Adaption und – wo lizenziert – Flashen. Herstellerübergreifend, mit breiter VCI-/Dongle-Unterstützung.

**Weiterführende Dokumente:** [Wirtschaftlichkeitsrechnung](wirtschaftlichkeitsrechnung.md) · [Investor One-Pager](investor-summary.md) · [Implementierungsplan (Code)](implementierungsplan.md) · [J2534-DLL-Spezifikation](j2534-dll-spezifikation.md) · [VCI-/Dongle-Matrix](vci-dongle-matrix.md)

---

## 0. Kurzfassung (das Wichtigste zuerst)

1. **Die App ist machbar. „Wie beim Hersteller-Launch" ist ohne Lizenz nicht machbar.** Der Unterschied liegt nicht in der App, sondern in drei Dingen: **SERMI-Zertifizierung**, **OEM-/Datenlizenzen** und **VCI-Zugang**. Alles drei sind Vertrags- und Rechtsfragen, keine Programmierfragen. Wer das ignoriert, baut 18 Monate und darf das Ergebnis nicht verkaufen.
2. **J2534 läuft nicht auf dem Tablet.** Die Norm schreibt eine Win32-DLL vor (SAE J2534-1 §7). Auf Android/iOS gibt es drei Wege: (a) mobile SDKs einzelner VCI-Hersteller, (b) ein Werkstatt-Gateway (Windows-Kiste) im LAN, das Passthru per IP bereitstellt, (c) eigene Hardware. Der Plan empfiehlt (a) + (b) parallel.
3. **Der Content ist das Produkt, nicht der Code.** Der Protokollstack (UDS/ISO-TP/DoIP) ist in 4–6 Monaten solide. Die 300 Service-Funktionen für 40 Marken sind 3–4 Jahre Arbeit eines eigenen Content-Teams mit Fahrzeugzugang.
4. **Empfohlener Zuschnitt für Version 1:** Diagnose + Service-Rückstellung für 15–20 Volumenmodelle, lückenlose Dokumentation, Tablet-first-Bedienung. Codierung in Version 2, Flashen in Version 3 (dann mit SERMI + Passthru).
5. **Nicht verhandelbar:** keine Tacho-Manipulation, keine Abschaltung von Abgas-/Sicherheitssystemen, keine Leistungssteigerung (SERMI schließt Tuning ausdrücklich aus), kein Reverse Engineering von Seed/Key-Algorithmen.

---

## 1. Was heißt „wie beim Hersteller"? — Drei Zugriffsstufen

Die Werkstattbranche unterscheidet faktisch drei Ebenen. Die App muss alle drei abbilden können, aber sie darf sie nicht vermischen — rechtlich und in der Bedienung nicht.

| Stufe | Datenquelle | Was möglich ist | Rechtlich | Aufwand |
|---|---|---|---|---|
| **A — Generisch** | Öffentliche Normen (ISO 15031/J1979, ISO 14229, ISO 15765, ISO 13400) + eigene Erhebung am Fahrzeug | EOBD-Diagnose, DTC lesen/löschen, Live-Daten, Readiness, viele Service-Rückstellungen (Öl, EPB, Lenkwinkel, Batterie, DPF-Standregeneration) | Frei, sofern am eigenen/pilotierten Fahrzeug erhoben | Mittel — aber pro Modell Erhebung nötig |
| **B — Lizenzierte Drittanbieter-Daten** | Datenanbieter (TecAlliance, Autodata, HaynesPro, Hella Gutmann, Bosch ESI, ODX-Lizenzen) | Codier-/Adaptionsdaten, Drehmomente, Diagnose-Geführt-Abläufe, teils SG-übergreifende Funktionen | Lizenzvertrag; Weitergabe in eigener App meist nur eingeschränkt erlaubt → **muss verhandelt werden** | Hoch (Vertrag + Integration) |
| **C — Herstellerniveau** | OEM-RMI-Portal (erWin, AOS, WIS, …), OEM-Passthru-Reprogrammierung, SERMI-Zugang | Schlüssel/Wegfahrsperre, Komponentenschutz, Variantencodierung, Flashen mit OEM-Dateien, Freischaltungen | **Nur mit SERMI-Zertifikat** (Betrieb + Techniker) und OEM-Nutzungsbedingungen | Sehr hoch (Prozess, Prüfung, Doku) |

**Konsequenz für die Produktstrategie:** Stufe C ist kein Feature, das man „einbaut", sondern ein Berechtigungsmodell. Die App wird technisch **ein Berechtigungs-Gate** haben: Eine Funktion der Klasse R4 (siehe §3) ist gesperrt, solange kein gültiges, personalisiertes SERMI-Zertifikat des angemeldeten Technikers vorliegt — und sie führt dann über das OEM-Portal/die OEM-Software, nicht über selbstgebaute Algorithmen.

### 1.1 Die harte Grenze, klar benannt

Seed/Key-Verfahren (Security Access, UDS `0x27`) der Hersteller sind Geschäftsgeheimnisse. Sie zu extrahieren, zu veröffentlichen oder zu umgehen ist in der EU nach GeschGehG angreifbar, verletzt die Nutzungsbedingungen der Portale und ist in Deutschland regelmäßig Gegenstand von Abmahnungen. Ein Produkt, das „alles wie der Hersteller" über nachgebaute Algorithmen verspricht, ist kein Feature-Vorsprung, sondern ein Insolvenzrisiko.

**Was stattdessen geht:** Die App orchestriert lizenzierte Zugänge besser als alle anderen. Genau da liegt der Wettbewerbsvorteil (siehe §11).

---

## 2. Rechtlicher Rahmen (Pflichtenheft vor der ersten Zeile Code)

| Thema | Regelwerk | Konsequenz für die App |
|---|---|---|
| Zugang zu sicherheitsrelevanter RMI | VO (EU) 2018/858 + **Delegierte VO (EU) 2021/1244 (SERMI)**; in DE seit **01.04.2024** aktiv | Betriebliche Freigabe **und** personalisiertes Zertifikat je Techniker; Nachweis von Prozessen, Dokumentation, Vertrauenswürdigkeit; Prüfung durch Konformitätsbewertungsstelle (z. B. SERMA, Certival, Kiwa, GNG) |
| SERMI im Alltag | SERMI-Schema (EA, 2023 aktualisiert) | Zertifikat + PIN sind personengebunden → App braucht **Techniker-Login**, keine geteilten Accounts, keine Weitergabe. Zertifikats-Link läuft ab (bei manchen Herstellern ~60 Tage) → Erinnerungs- und Sperrlogik |
| Reparaturinformation allg. | VO (EU) 2018/858 Anhang X | nicht-diskriminierender Zugang; Abo-Modelle der Hersteller sind zulässig → Kostenfaktor einplanen |
| Emissionsrelevante Reprogrammierung | **SAE J2534-1/-2**, EPA/CARB-Vorgaben (USA), EU-Praxis | J2534-Passthru ist der legale Weg zum Flashen; Hersteller stellen Software/Abos bereit |
| Software-Update am Fahrzeug | **UNECE R155 (CSMS)**, **R156 (SUMS)**, ISO 24089 | Wer Software auf typgenehmigte Fahrzeuge aufspielt, braucht ein Update-Management-System: Versionierung, Rückverfolgbarkeit, Freigabe, Rollback, Dokumentation (RXSWIN) |
| Datenschutz | DSGVO, Art. 5/6/32 | VIN ist personenbeziehbar; Fahrzeugdaten, Kundenkontakt, Technikerstandort minimieren; AVV mit Werkstattkunden; Löschkonzept; Verschlüsselung |
| Produkthaftung | ProdHaftG, ProdSG, BGB-Gewährleistung | Fehlerhafte Codierung = Haftungsfall → Audit-Log, Vorher-/Nachher-Backup, Bedienerbestätigung sind **Pflicht**, nicht Komfort |
| Zulassungsrecht | § 19 Abs. 2 StVZO | Änderungen an Abgas-, Geräusch-, Leistungsverhalten lassen die Betriebserlaubnis erlöschen → Funktionen, die das tun, werden nicht gebaut |
| Strafrecht | u. a. Urkunden-/Datenstrafrecht, Tacho-Manipulation | Manipulation von Laufleistung und Fahrzeugidentität ist strafbar → harte technische Sperre, kein „Expertenmodus" |
| Marken-/Wettbewerbsrecht | UWG, MarkenG, UrhG § 69d | keine OEM-Logos/-Screens, keine als „Original" getarnte Oberfläche; Interoperabilität ist erlaubt, Nachbau von Tool-Oberflächen nicht |
| Treiber-/SDK-Lizenzen | VCI-Hersteller-NDA | SDK-Weitergabe, Zertifizierungs-Logos und Support-Zusagen vertraglich klären, **bevor** die Matrix in §5 als Versprechen nach außen geht |

**Arbeitsauftrag vor M1:** ein Rechtsgutachten (2–3 Wochen, 5–15 T€) zu SERMI-Einbindung, J2534-Redistribution und Datenlizenzen. Das ist die günstigste Investition des ganzen Projekts.

---

## 3. Funktionsumfang & Risikoklassen

Jede Funktion bekommt eine Risikoklasse. Die Klasse steuert UI, Freigaben, Pflicht-Backups und ob SERMI nötig ist.

| Klasse | Bedeutung | Beispiele | Guardrails |
|---|---|---|---|
| **R0** | Nur lesen | DTC, Live-Werte, Freeze Frame, Readiness, SG-Identifikation | keine |
| **R1** | Schreiben, unkritisch/reversibel | Service-Intervall-Reset, Ölservice, Inspektionsanzeige, Reifendruck-Set, Fensterheber-Normalisierung | Bestätigung, Read-Back |
| **R2** | Schreiben mit Zustandsänderung | EPB-Wartungsmodus, Lenkwinkel-/SAS-Kalibrierung, Batterieregistrierung, DPF-Standregeneration, Drosselklappen-/Getriebe-Lernwerte, Injektorcodiierung | **Backup vor Write**, Spannungs-/Klemmen-Check, Diff-Anzeige, Restore |
| **R3** | Irreversibel/teuer | Variantencodierung, Parameter-Sets, Flashen/Update, Getriebe-Adaption, Komponententausch-Anlernen | Zwei-Personen-Freigabe, Netzteilpflicht, Protokoll, Abbruch-Recovery |
| **R4** | Sicherheitsrelevant (Diebstahlschutz) | Schlüssel anlernen, Wegfahrsperre, Komponentenschutz, Tür-/Zündschloss-SG, teils Codierung sicherheitsrelevanter SG | **SERMI-Zertifikat + PIN + Doku**, ggf. Herstellerfreigabe; niemals offline |

### 3.1 Feature-Katalog (Versionen)

**F1 Fahrzeugidentifikation** — VIN aus `0x22 F190`/`0x09 02`, Gateway-Scan, Modell-/Baujahr-Ableitung, HSN/TSN, Fallback manuell. Automatische Wahl des Funktions-Sets.

**F2 Diagnose** — DTC lesen/löschen (alle SG), Live-Daten (frei konfigurierbare Kacheln, Logging, CSV/PDF), Freeze Frames, Readiness/Monitor-Status, Stellglied-Tests (nur wo dokumentiert freigegeben), geführte Funktionsprüfungen, Batteriespannungs-Messung über VCI.

**F3 Service-Rückstellung (Kernnutzen, Version 1)** — Katalog pro Modell/Funktion: Öl-/Inspektionsservice, EPB-Wartungsmodus, DPF-Regeneration/Aschestand, AdBlue-/SCR-Reset, Batterieregistrierung (AGM/EFB, Kapazität), Lenkwinkelsensor, Reifendruck-Set, Getriebeöl-/Adaptionswerte, Drosselklappe, AGR-/Kühlerlernwerte, Panoramadach/Fensterheber, Lenkhilfe-Kalibrierung, Fahrwerk-/Luftfeder-Nullage, Spurhalte-/Kamera-Kalibrierung (nur wo zulässig).

**F4 Codierung/Adaption (Version 2)** — Variantencodierung, Parameteränderung, Codier-Backup und -Restore, Diff zwischen zwei Fahrzeugen, Codier-Historie, Plausibilitäts-/Checksummen-Prüfung.

**F5 Flashen/Update (Version 3)** — nur Passthru/OEM: Datei-Download mit Signaturprüfung, Spannungs-Interlock, Resume, Recovery, RXSWIN-/Versions-Dokumentation.

**F6 Dokumentation/Auftrag** — Fahrzeugakte, Auftrag, Kunde (Name/Kontakt), Fotos, Vorher-/Nachher, digitaler Kundenbericht (PDF), Freigabe-Unterschrift auf dem Tablet, Audit-Log (append-only, exportierbar für Prüfungen).

**F7 Team & Rechte** — Rollen (Inhaber/Meister/Geselle/Azubi), Zwei-Personen-Prinzip für R3/R4, Techniker-Zertifikatsbindung, Schicht-/Zeitfreigaben, Sperrliste.

**F8 Offline-First** — Diagnose muss ohne Internet funktionieren (Werkstatt-Halle!). Content-Pakete lokal, signiert, versioniert; Sync im Hintergrund.

**F9 Auswertung** — Durchlaufzeiten, häufigste DTC, Umsatz je Service-Funktion (nur lokal/opt-in), Export.

**F10 Optional später** — Fernsupport (Senior sieht den Bildschirm), Mehrmandanten (Filialen), Teile-/Bestell-Anbindung, Kundenannahme-Terminal.

**Explizit nicht im Scope:** Tuning/Leistungssteigerung, Abschalten von AGR/DPF/AdBlue, Odometer-Änderung, Freischalten von Abo-Funktionen, Bypass von Wegfahrsperren außerhalb SERMI.

### 3.2 Sicherheits-Interlocks (gelten für jedes Schreiben)

1. Klemme-15-Status und Motorzustand plausibel (aus Live-Daten, nicht nur Vermutung).
2. Batteriespannung ≥ herstellerspezifischem Schwellwert, sonst Abbruch mit Hinweis auf Ladegerät.
3. VCI-Selbsttest + Firmware-Version aktuell.
4. Fahrzeug-Read-Back **vor** dem Schreiben (Codier-Block vollständig lesen, Hash speichern).
5. Dry-Run/Simulation, wo möglich: zeigt exakt die zu schreibenden Bytes.
6. Abbruch jederzeit möglich, aber nur mit garantiertem definiertem Endzustand (Recovery-Sequenz je Funktion).
7. Nach Write: erneutes Lesen und Diff — „OK" wird nur gemeldet, wenn der Read-Back zum Sollwert passt.

---

## 4. Technische Architektur

```
┌─────────────── Tablet / Smartphone ───────────────┐
│  UI:  Jetpack Compose (Android)  │  SwiftUI (iOS) │
│  ────────────────────────────────────────────────  │
│  Domain/UseCases, Session-Manager, Auftragslogik   │
│  ────────────────────────────────────────────────  │
│  Rust-Core (via UniFFI an Kotlin & Swift)          │
│   isotp · doip · uds · kwp · j1979 · j1939 ·       │
│   function-runtime · coding · flash · odx ·        │
│   audit · crypto · vci-hal                         │
│  ────────────────────────────────────────────────  │
│  VCI-HAL: USB / BLE / WLAN / IP-Gateway            │
└───────────────────────┬───────────────────────────┘
                        │  (BT/USB/WLAN/IP)
                 ┌──────┴───────┐
                 │  VCI / Dongle │──── OBD-Buchse ──── Fahrzeug
                 └──────┬───────┘
                        │ optional
              ┌─────────┴──────────┐
              │ Werkstatt-Gateway   │  Windows-Mini-PC im LAN:
              │ (J2534 / D-PDU API) │  stellt OEM-Passthru & J2534-DLLs
              └────────────────────┘  als Netzwerkdienst bereit
```

### 4.1 Warum zwei native Apps **plus** Rust-Core

- **Nativ bleibt nativ:** Kamera-Scan der VIN, BLE-Stack, Dateisystem, Hintergrund-Sync, Split-View auf iPad, Stift/Unterschrift, Handschuh-/Sonnenlicht-Themen — alles Dinge, bei denen Cross-UI-Frameworks messbar schlechter sind. Der Kunde bezahlt hier für Werkstatt-Robustheit, nicht für eine Website in der Hülle.
- **Rust im Kern** aus drei Gründen: (1) ein Protokollstack für beide Plattformen statt zwei Implementierungen mit unterschiedlichen Timing-Bugs, (2) deterministisches Verhalten bei ISO-TP/CAN-FD-Timing (P2/P2\*), (3) Speichersicherheit bei Parser-Code, der fremde Bytes von fremden Steuergeräten verarbeitet. Bindung über **UniFFI** (stabile Kotlin-/Swift-APIs, kein JNI-Handwerk). Die J2534-DLL bleibt davon getrennt in C, weil sie in fremden Prozessen läuft — Begründung, Folgen und Aufwand: [ADR-001](adr-001-kernsprache.md).
- **Kotlin Multiplatform** wäre die Alternative, wenn das Team kein Rust kann. Dann bleibt der Protokoll-Timing-Teil trotzdem nativ/C. Entscheidung ist eine Personalentscheidung, nicht eine Architekturfrage — beide Wege sind tragfähig.
- **Kein Flutter/React Native für den Kern.** Für das UI ist es Geschmackssache; für Bus-Timing und VCI-Hot-Plug ist es eine Fehlerquelle, die man in der Werkstatt teuer bezahlt.

### 4.2 Plattform-Minima

| | Android | iOS/iPadOS |
|---|---|---|
| Sprache/UI | Kotlin, Compose, Material 3 adaptive Layouts | Swift, SwiftUI, iPad Multitasking/Split-View |
| Minimum | Android 10 (API 29), empfohlen 12+ | iOS/iPadOS 17+ |
| Geräteklassen | 8–13" Tablets (Werkstatt), 6" Phone | iPad 10"+, iPhone |
| Besonderheiten | USB-Host (Passthru-Kabel), BLE, WLAN-Direct, Kiosk-Modus | BLE, WLAN, kein freier USB-Zugriff → **USB-VCIs nur über BLE/WLAN/IP**; MFi prüfen |
| Verteilung | Play Store + Managed Google Play (Werkstatt-Flotten) | App Store + Apple Business Manager |

> **Wichtig für die Dongle-Strategie:** iOS kann USB-Passthru-Kabel nicht generisch ansprechen. Jede VCI-Klasse, die „USB-Kabel" ist, braucht auf iOS entweder MFi-Zertifizierung oder einen WLAN/BLE-Zwischenweg. Das ist der stärkste Grund für die Gateway-Lösung (§5.3).

### 4.3 Kernmodule (Rust)

| Modul | Inhalt | Reifegrad-Ziel |
|---|---|---|
`vci-hal` | Trait `VehicleInterface`: open/close, Konfiguration, `send/recv` mit Zeitstempel, Capabilities (CAN, CAN FD, K-Line, LIN, DoIP, Pins, Spannungsmessung), Firmware-/Self-Test | M1
`isotp` | ISO 15765-2: Single/First/Consecutive/FlowControl, Extended/Normal Addressing, Padding, ISO-TP-Parameter pro Fahrzeug, Timeout-/Retry-Policy, Fuzzing-Härtung | M1
`can` | Raw CAN/CAN FD, 11/29-bit, Bitraten-Sets, Bus-Last-Anzeige | M1
`doip` | ISO 13400-2: Vehicle Announcement, Routing Activation, Alive Check, TLS-Option, Logical Addresses | M2
`uds` | ISO 14229-1: `0x10,0x11,0x14,0x19,0x22,0x27,0x28,0x2E,0x2F,0x31,0x34–0x37,0x3E,0x85`; NRC-Handling inkl. `0x78` responsePending, P2/P2\*-Steuerung, TesterPresent | M1
`kwp` | ISO 14230 (KWP2000), 5-Baud-Init, Fast-Init — für Fahrzeuge bis ~2008 | M2
`j1979` | OBD-II/EOBD-Dienste `01–0A`, PID-Katalog, Readiness | M1
`j1939` | Nutzfahrzeug (später, eigene Marktstrategie) | M4
`function-runtime` | Datengetriebene Werkstattfunktion: Preconditions → Steps → Postconditions → Rollback; Dry-Run; Fortschritt; Abbruch | M1
`coding` | Voll-Read, Diff, Soll-Ist-Abgleich, Backup/Restore, Checksummen-/Plausibilitätsprüfung | M2
`flash` | Download, Signaturprüfung, Resume, Spannungs-Interlock, Recovery | M3
`secaccess` | **Provider-Schnittstelle** für Security Access: nur lizenzierte/kundeneigene Provider, kein eingebauter Algorithmus. Challenge/Response wird protokolliert, nicht gespeichert | M2
`audit` | Append-only-Log (Hash-Kette), Export, Prüfbericht | M1
`crypto` | Signaturprüfung der Content-Pakete (Ed25519), verschlüsselte lokale DB, Key-Rotation | M1

### 4.4 Datengetrieben statt Code pro Marke

Der teuerste Fehler wäre, jede Marke als Codepfad zu schreiben. Stattdessen:

- **Function Definition** (eigenes, versioniertes YAML/Protobuf-Format): Fahrzeug-Match (VIN-Muster/HSN/TSN/SG-IDs), Voraussetzungen, Requests/Responses, erwartete Werte, Recovery, Risikoklasse, Quelle/Provenienz, Freigabe-Level.
- **ODX/PDX (ISO 22901)** dort, wo lizenziert vorhanden — sonst nicht erzwingen: ODX-Kompetenz ist rar und teuer; ein schlankes eigenes Format mit klarer Semantik reicht für Werkstattfunktionen und ist schneller zu pflegen.
- **OTX (ISO 13209)** optional für komplexe geführte Abläufe (später).
- **Content-Pipeline:** Editor → Review durch Kfz-Meister → Test am Fahrzeug (Evidence-Log) → Signierung → Release → Rollback. Jede Funktion trägt ihre Quelle und ihren Freigabestatus im Datensatz.
- **Versionierung:** Content-Pakete signiert, pro Fahrzeug-Match gepinnt, damit ein Update nicht unbemerkt eine Funktion ändert.

### 4.5 Backend

| Baustein | Aufgabe |
|---|---|
API-Gateway | REST/GraphQL, mTLS, Rate-Limits, Audit-Header
Auth | OIDC/SAML, SSO für Werkstattketten, MFA für R3/R4
Mandanten | Werkstatt → Standort → Team → Techniker, Rollen
Lizenz/Abo | Funktionspakete pro Werkstatt, Content-Abos, Testlizenzen
Content-CDN | signierte Pakete, Delta-Updates, Rollback-Ziele
Audit-Store | append-only, revisionssicher, Export für SERMI-/Betriebsprüfung
Diagnose-Telemetrie | **opt-in**, anonymisiert, Crash-Logs mit Schwärzung von VIN/Kundendaten
Key-Management | HSM/KMS, Signaturschlüssel nie im Client, Rotation, Split-Custody
Feature-Flags | Funktion × Fahrzeug × Land × Lizenz, Kill-Switch bei Feldproblemen
Support-Konsole | Werkstatt-Historie, Session-Replay (ohne Personendaten)

### 4.6 Security

- Transport: TLS 1.3 + Certificate Pinning; Gateway-Verbindung zusätzlich HMAC-signiert.
- Gerät: Play Integrity / App Attest, verschlüsselte DB (SQLCipher/Data Protection), Biometrie für R3/R4.
- Keine Secrets im Client; Content-Pakete signiert; Rollback-Schutz gegen Downgrade-Angriffe.
- SBOM, reproduzierbare Builds, jährlicher Pen-Test, Koordinierte Schwachstellenmeldung.
- Bedrohungsmodell explizit: gestohlene Tablets, manipulierte Content-Pakete, gefälschte VCIs, „Werkstatt-Account-Sharing" (SERMI-relevant!).

### 4.7 Teststrategie (HIL statt Hoffnung)

- **ECU-Prüfstand:** Motor-SG, BCM, Gateway, EPB, Klima, Getriebe (2–3 Generationen), 12-V-Netzteil mit Spannungseinbrüchen, Restbussimulation.
- **Bus-Analyse:** Vector VN1630/VN1640, PEAK PCAN, Kvaser — zum Mitschneiden und für Golden-Logs.
- **Golden-Logs & Replay:** jede Funktion hat aufgezeichnete Referenz-Sequenzen; Regressionstests laufen im CI gegen den Simulator.
- **Fuzzing** des ISO-TP-/UDS-Parsers (fehlerhafte Steuergeräte sind real).
- **Beta-Feld:** 3–5 Pilotwerkstätten, Shadow-Mode (App liest mit, schreibt nicht), dann stufenweise Freigabe pro Funktion.
- **Abnahmekriterien pro Funktion:** Dry-Run korrekt, Write + Read-Back korrekt, Abbruch mitten im Ablauf führt zu definiertem Zustand, Doku-PDF korrekt, Audit-Eintrag vollständig.

---

## 5. VCI-/Dongle-Strategie — der technische Knackpunkt

### 5.1 Die unbequeme Wahrheit über J2534

SAE J2534-1 legt in §7 eine **32-Bit-Windows-DLL** als API fest (`PassThruOpen`, `PassThruConnect`, …). Ein Android- oder iOS-Prozess kann diese DLL nicht laden. „Wir unterstützen alle J2534-Dongles" ist auf einem Tablet also **kein Satz, der sich durch Programmieren wahr machen lässt** — er braucht einen der drei folgenden Wege.

### 5.2 Drei Wege (Empfehlung: 1 + 2 parallel)

| Weg | Wie | Vorteil | Nachteil |
|---|---|---|---|
**1. Mobile SDKs der VCI-Hersteller** | Hersteller liefern J2534-kompatible Bibliotheken auch für Android/iOS (z. B. Quantex ScanDoc: Builds für Android und iOS-XCFramework) oder eigene BLE/WLAN-SDKs (OBDLink/STN, Hersteller-SDKs) | echte App-Integration, ein Gerät, kein PC | pro Hersteller Vertrag/NDA, Support-Matrix, iOS-Einschränkungen |
**2. Werkstatt-Gateway (Windows-Mini-PC im LAN)** | Gateway stellt J2534-Instanzen + OEM-Passthru als Netzwerkdienst bereit; Tablet ist Bedienoberfläche | deckt **alle** J2534-Dongles und OEM-Tools ab, Flashen möglich, Updates zentral | zusätzliche Hardware (~300–600 €), Setup-Aufwand, „noch eine Kiste" |
**3. Eigene Hardware** | eigenes VCI (2× CAN FD, K-Line, LIN, DoIP, Power-Management), CE/FCC | volle Kontrolle, Marge, Marke | 12–18 Monate, EMV-/Zulassungsaufwand, Firmware-Lebenszyklus |

**Beschlussvorschlag:** Version 1 startet mit **Weg 1** (2–3 zertifizierte VCIs, BLE/WLAN, plus ein USB-Modell für Android) und **Weg 2 als optionale Gateway-Erweiterung** für Passthru/Flashen. **Weg 3** erst prüfen, wenn Content und Kundenbasis stehen.

### 5.3 Konkrete Gerätelandschaft

Die vollständige Matrix steht in [`vci-dongle-matrix.md`](vci-dongle-matrix.md). Kurzfassung:

- **Klasse 1 — generische OBD-Adapter (BLE/WLAN):** OBDLink MX+ (STN2256), OBDLink CX (STN2310), vLinker-Äquivalente. Für Diagnose/Service-Rückstellung brauchbar, **nicht** zum Flashen.
- **Klasse 2 — Profi-Passthru (J2534):** Drew Technologies CarDAQ-Plus 3, Tactrix OpenPort 2.0, Quantex ScanDoc, Mongoose-Plus, Bosch MVCI, Softing VCI, Kvaser/PEAK-Interfaces mit J2534-DLL. Über Gateway (Weg 2) oder mobile SDK (Weg 1).
- **Klasse 3 — DoIP/Ethernet:** VAS 6154 (VAG), BMW ICOM Next, Mercedes XENTRY VCI/SDconnect, DoIP ohne VCI. Meist OEM-Software-gebunden → nur über OEM-Portal, nicht als „Dongle-Support" bewerben.
- **Klasse 4 — OEM-Werkstatttools** (VCM II/3, MDI2, microPod, Consult-III Plus, MUT-III, CLIP, DiCE, HDS, GDS …): **nicht integrierbar ohne Herstellerlizenz**. In der App klar als „nicht unterstützt" geführt, statt falsche Erwartungen zu wecken.
- **Nicht unterstützt (bewusst):** ELM327-Clones. Kein zuverlässiges Multi-Frame-ISO-TP, keine belastbare Security-Access-Sequenz, enorme Streuung der Firmware. Wenn überhaupt: reiner Lesemodus mit deutlicher Warnung.

### 5.4 Was „Dongle-Kompatibilität" praktisch bedeutet

1. **Capability-Modell statt Geräteliste:** Die App fragt das VCI nach Fähigkeiten (CAN FD? DoIP? K-Line? Pins? Spannung?), nicht nach dem Produktnamen.
2. **Zertifizierte Matrix mit Reifegrad:** je Gerät „getestet / eingeschränkt / nicht unterstützt", mit Firmware-Mindestversion.
3. **Selbsttest & Diagnose im Feld:** ein „VCI-Checker" in der App (Bus-Widerstand, Spannung, Loopback, Firmware, Lizenz) — spart 80 % des Supports.
4. **Keine Versprechen außerhalb der Matrix:** Marketing darf nur nennen, was in der getesteten Matrix steht.

---

## 6. Content & Daten (das eigentliche Produkt)

| Quelle | Was sie liefert | Hürde |
|---|---|---|
OEM-RMI-Portale (erWin, AOS, WIS/ASRA, …) | herstellernahe Abläufe, Codierhinweise, Flaschanleitungen | Abo je Marke/Zeit, ToS verbieten Automatisierung/Scraping → Vertrag oder manuelle Kuratierung |
Datenanbieter (TecAlliance, Autodata, HaynesPro, Hella Gutmann, Bosch ESI) | Fahrzeugdaten, Wartungspläne, Reparaturzeiten, teils Diagnoseabläufe | Lizenzkosten, Weitergabe in eigener App oft untersagt → **explizit verhandeln** |
ODX/PDX-Lizenzen | formale SG-Beschreibungen | teuer, wenig Verfügbarkeit, Pflegeaufwand |
Eigene Erhebung | genau die Funktionen, die Werkstätten wirklich brauchen | Fahrzeuge + Meisterzeit; dafür sauber lizenzierbar und differenzierend |

**Empfehlung:** Start mit **eigener Erhebung** für 15–20 Volumenmodelle (die 80 % der Aufträge in freien Werkstätten), ergänzt um einen Datenanbieter-Vertrag für Fahrzeug-Stammdaten. OEM-Portale nur als Arbeitsmittel für das eigene Content-Team, nicht als Datenquelle für ein automatisiertes Produkt — solange kein Vertrag etwas anderes sagt.

**Content-Team = Kerninvestition.** Größenordnung: 1 Kfz-Meister (Freigabe), 1–2 Content-Engineers (Funktionsdefinitionen), 1 Fahrzeug-Logistiker, anteilig QA. Ohne dieses Team ist die App eine schöne Hülle mit 20 Funktionen.

---

## 7. UX-Konzept (Werkstattrealität, nicht Bürorealität)

- **Zwei Geräte-Rollen:** Tablet = geführte Diagnose am Fahrzeug, große Live-Kacheln, Schaltplan-/Anleitungs-Split beim iPad; Phone = Scan, Auftrag, Kundenübergabe, Fotos, Freigabe-Unterschrift.
- **Handschuh- und Sonnenlicht-Tauglichkeit:** Touch-Ziele ≥ 56 dp, hoher Kontrast, kein zartes Grau, große Typo, Dark-Theme für Hallenlicht, keine Hover-Abhängigkeiten.
- **Drei-Tap-Regel:** Ölservice zurückstellen = Fahrzeug wählen → Funktion → Bestätigen. Alles andere ist Bedienfehler.
- **Zustand immer sichtbar:** Klemme 15, Spannung, SG-Online-Status, aktive Session — dauerhaft im Kopfbereich. Kein „blinder" Schreibvorgang.
- **Farbcodierte Risikoklassen:** grau (lesen), grün (R1), gelb (R2), orange (R3), rot (R4/SERMI) — und die Farbe ist nicht die einzige Information (Barrierefreiheit: Icon + Text).
- **Fehlerkultur:** Nie „OK" ohne Read-Back. Immer Vorher/Nachher. Abbruch ist immer erlaubt, aber erklärt, was bleibt.
- **Kundengespräch inklusive:** Klartext-Übersetzung des DTC („Lambdasonde Bank 1 – Signal zu mager") + Kostenschätzung + Freigabe-Unterschrift.
- **Mehrsprachig** ab Tag 1: DE/EN im Code, weitere über Content-Pakete.
- **Offline sichtbar:** Sync-Status, „arbeitet offline" ist normal, nicht ein Fehler.

---

## 8. Roadmap

| Meilenstein | Zeitraum | Inhalt | Exit-Kriterium |
|---|---|---|---|
**M0 Discovery & Recht** | Monat 0–2 | Rechtsgutachten SERMI/J2534/Daten; VCI-Partner (2–3 NDAs); Datenanbieter-LOIs; Pilotwerkstätten (3–5); Feinschnitt Version 1 | schriftliche Rechtslage + unterschriebene Partner |
**M1 Diagnose-Basis** | Monat 2–8 | Rust-Core (`isotp`, `uds`, `j1979`, `vci-hal`), Android+iOS-App, BLE-VCI, Fahrzeugidentifikation, DTC/Live/Freeze-Frame, Audit, Auftragsdoku, 3 VCIs zertifiziert, 10 Service-Funktionen auf 10 Modellen | Feldtest bei Pilotwerkstätten ohne Datenverlust; Absturzrate < 0,5 % |
**M2 Service & Codierung** | Monat 8–15 | Service-Katalog auf 15–20 Modelle (60–100 Funktionen), DoIP, KWP, Codierung R2 inkl. Backup/Restore/Diff, Gateway-Option, Team/Rollen | 95 % der Funktionen mit Read-Back-Nachweis; Restore in 100 % der Testfälle |
**M3 Passthru & SERMI** | Monat 15–22 | J2534 über Gateway, Flashen mit Interlock/Recovery, SERMI-Berechtigungsmodell (Technikerzertifikat, Ablauf, Doku-Export), SUMS-Prozess | Flashen auf 5 Modellen + Recovery-Test bestanden; SERMI-Prüfung im Pilotbetrieb |
**M4 Skalierung** | Monat 22+ | 40+ Marken, Nutzfahrzeug (J1939), Fernsupport, Mehrmandant, weitere Sprachen | Content-Pipeline trägt > 10 Funktionen/Woche |

**Realistische Erwartung:** Marktreife für „Diagnose + Service-Rückstellung, herstellerübergreifend, breite Dongle-Basis" liegt bei **12 Monaten** (Variante V1, 7,5 FTE) bzw. **18 Monaten** (empfohlene V2, 9,5 FTE) — Werte aus der [Wirtschaftlichkeitsrechnung](wirtschaftlichkeitsrechnung.md) §2, gerechnet mit `model/wirtschaftlichkeit.py`, nicht geschätzt. „Codierung + Flashen auf Herstellerniveau" braucht **2+ Jahre** und ist ein Geschäftsmodell mit laufenden Lizenzkosten, kein einmaliges Projekt.

---

## 9. Team, Budget, Betrieb (Schätzung mit Annahmen)

| Rolle | FTE | Ab wann |
|---|---|---|
Android-Entwicklung (Kotlin/Compose) | 1,5 | M1
iOS-Entwicklung (Swift/SwiftUI) | 1,5 | M1
Rust-/Protokoll-Entwicklung | 2 | M1
Backend/Cloud | 1 | M1
Content-Engineering (Funktionsdefinitionen) | 1,5 | M1
Kfz-Meister / fachliche Freigabe (Teilzeit) | 0,5 | M1
QA mit Fahrzeug-/HIL-Zugang | 1 | M1
Security/Compliance (anteilig) | 0,3 | M1
Produkt/Projektleitung | 1 | M0
Recht/Einkauf (extern) | — | M0

**Kostenrahmen (Vollkosten, DE, 24 Monate bis M3):** grob **2,5–4,0 Mio €** inkl. Hardware-Lab und Fahrzeugzugang, **ohne** Content-/Datenlizenzen. Die Entwicklungssumme allein beträgt im Modell **1,33 Mio € (V1) / 2,24 Mio € (V2) / 3,18 Mio € (V3)** — Lab, Fahrzeuge und Prüfstand sind dort bereits als Sachkosten enthalten; der Rahmen darüber deckt zusätzliche Monate bis M3 und Reserven ab.
**Detaillierte Rechnung:** [Wirtschaftlichkeitsrechnung](wirtschaftlichkeitsrechnung.md) — Entwicklung 1,33 / 2,24 / 3,18 Mio € je Ausbauvariante, Break-even-Matrix je Marktabdeckung, Kapitalbedarf und Preishebel. **Wichtig:** Der Kapitalbedarf (3,4 Mio € im schlanken Fall bis 16,0 Mio € bei reinem Direktvertrieb im Herstellerniveau; empfohlene Konstellation **6,07 Mio €**) liegt deutlich über der Entwicklungssumme, weil die Anlaufverluste bis zur Kundenbasis hinzukommen.
**Laufend:** Content-Pflege 0,4–1,2 Mio €/Jahr je Marktabdeckung; Datenlizenzen je Vertrag; SERMI-Prüfung/Zertifikate pro Betrieb und Techniker (vierstellig pro Betrieb und Jahr, Gebühren beim CAB erfragen); Cloud-/Support-/Store-Kosten.

**Hardware-Startpaket (einmalig, ~25–40 T€):** 2–3 Fahrzeuge bzw. SG-Set, Motor-/BCM-/Gateway-Prüfstand, Restbussimulation, 12-V-Netzteil mit Programmierfunktion, Vector/PEAK/Kvaser-Interfaces, Oszilloskop, 3–5 zertifizierte VCIs je Klasse, Prüfstands-Rechner, Gateway-Testgerät.

---

## 10. Risiko-Register (Top 10)

| # | Risiko | W | A | Gegenmaßnahme |
|---|---|---|---|---|
1 | „Herstellerniveau" ist ohne Lizenz nicht lieferbar → Erwartungsbruch | hoch | hoch | Positionierung von Tag 1: „lizenzierter Zugang, kein Nachbau"; Stufenmodell offen kommunizieren |
2 | SERMI-Zertifizierung der Kundenbetriebe fehlt → R4-Funktionen unverkäuflich | mittel | hoch | R4 als optionales Modul; App führt durch den Zertifizierungsprozess |
3 | Datenlizenz nicht erhältlich / zu teuer | hoch | hoch | eigener Content als Rückgrat; Anbieter nur additiv |
4 | VCI-Hersteller verweigert SDK/mobile Build | mittel | hoch | Gateway-Weg von Anfang an mitbauen; 2–3 Hersteller parallel |
5 | iOS + USB-Passthru technisch unmöglich | hoch | mittel | iPad nur BLE/WLAN/IP; USB-Passthru = Gateway oder Android |
6 | Fehlerhafte Codierung beschädigt Fahrzeug → Haftung/Reputation | mittel | sehr hoch | Interlocks, Backup, Read-Back, Dry-Run, Beta-Phasen, Versicherung |
7 | Timing-/Protokollfehler bei CAN FD und schnellen Gateways | mittel | hoch | Rust-Core, HIL-Tests, Fuzzing, Golden-Logs |
8 | Content-Pflege skaliert nicht (jedes Modell ein Sonderfall) | hoch | hoch | striktes Datenformat, Content-Pipeline, Priorisierung nach Auftragsvolumen |
9 | Marken-/Rechtsstreit wegen UI/Algorithmen | niedrig | sehr hoch | keine OEM-Optik, kein Reverse Engineering, dokumentierte Provenienz |
10 | Support-Last durch VCI-Vielfalt | hoch | mittel | zertifizierte Matrix, VCI-Checker, klare „nicht unterstützt"-Liste |

---

## 11. Markt & Differenzierung (kurz)

Der Markt ist besetzt: Autel, Launch, Bosch, Hella Gutmann, Texa, Delphi, dazu Consumer-Apps (Carly & Co.). **Man gewinnt hier nicht mit „mehr Funktionen"** — das ist ein Wettrüsten, das nur mit Content-Budget gewinnbar ist. Gewinnbar ist:

- **Workflow:** Auftrag → Diagnose → Doku → Kundenfreigabe → Rechnung in einem Fluss, ohne Zettel.
- **Tablet-first-Bedienung** statt Windows-Laptop mit Touch-Firnis.
- **Ehrliche Kompetenzanzeige:** pro Fahrzeug sichtbar, welche Funktion verlässlich geht („getestet am 2024er Golf") statt „unterstützt VW".
- **Team- und Nachweisfähigkeit:** Audit-Log, Zwei-Personen-Prinzip, SERMI-Tauglichkeit — das verkauft sich an Werkstattketten und Versicherer.
- **Preis-/Abo-Modell** pro Werkstatt statt 4.000-€-Koffer.

---

## 12. Nächste Schritte (die kommenden 4 Wochen)

1. **Rechtsgutachten beauftragen** (SERMI-Einbindung, J2534-Redistribution, Datenlizenzen, Haftung) — blockiert alles andere.
2. **VCI-Partner:** 3 NDAs + Tech-Deep-Dive (mobile Builds? DoIP? CAN FD? Zertifizierung? Stückpreise?).
3. **Datenanbieter:** 2 LOIs mit expliziter Erlaubnis zur Einbettung in eine Dritt-App.
4. **Pilotwerkstätten:** 3–5 Betriebe, davon 1 mit SERMI-Zertifikat, 1 Kettenbetrieb.
5. **Technischer Spike (4 Wochen):** Rust-`isotp`+`uds` auf Android und iOS gegen **zwei echte Fahrzeuge**, inkl. Lese-Test und einem Service-Reset (Öl) — beweist die Machbarkeit von Weg 1.
6. **Content-Schnitt:** 15 Volumenmodelle und 30 wichtigste Funktionen nach Auftragsvolumen priorisieren.
7. **Entscheidungen herbeiführen:** siehe §13.

---

## 13. Offene Entscheidungen (Auftraggeber)

| # | Entscheidung | Optionen | Empfehlung |
|---|---|---|---|
1 | Zielsegment | Freie Mehrmarken-Werkstatt / Vertragswerkstatt / Kette | freie Mehrmarken-Werkstatt (Volumen) |
2 | Tiefe in Version 1 | nur Diagnose+Service / + Codierung / + Flashen | Diagnose+Service (schnellster Wert) |
3 | VCI-Strategie | nur mobile SDKs / + Gateway / eigene Hardware | mobile SDKs + Gateway |
4 | Technologie | Rust-Core / KMP / je Plattform nativ | Rust-Core (bei Rust-Kompetenz), sonst KMP |
5 | Content | eigener Content / Datenanbieter / Mischung | Mischung, eigener Content als Kern |
6 | Geschäftsmodell | Kauf / Abo / Funktionspakete | Abo + Funktionspakete |
7 | Länder | DE/AT/CH / EU / global | DACH zuerst (SERMI-Verfügbarkeit prüfen) |

---

### Anhang: Normen- und Regelwerk-Referenz

`ISO 15031 / SAE J1979` OBD-II · `ISO 14229-1` UDS · `ISO 15765-2` ISO-TP · `ISO 11898-1/-2` CAN/CAN FD · `ISO 13400-2` DoIP · `ISO 14230` KWP2000 · `ISO 9141` K-Line · `SAE J1850` · `SAE J1939` · `SAE J2534-1/-2` Passthru · `ISO 22900-2` D-PDU API · `ISO 22901` ODX · `ISO 13209` OTX · `ASAM MCD-3D` · `ISO 24089` Software-Update-Engineering · `UNECE R155` Cybersecurity · `UNECE R156` Software Updates · `VO (EU) 2018/858` + `2021/1244` (SERMI) · `DSGVO` · `ProdHaftG` · `StVZO § 19(2)`

*Alle Zeit-, Budget- und FTE-Angaben sind Planungsannahmen für ein deutsches/europäisches Marktumfeld und vor Investitionsfreigabe zu validieren. Rechtsaussagen sind eine Planungshilfe, keine Rechtsberatung.*
