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
- **Rust im Kern** aus drei Gründen: (1) ein Protokollstack für beide Plattformen statt zwei Implementierungen mit unterschiedlichen Timing-Bugs, (2) deterministisches Verhalten bei ISO-TP/CAN-FD-Timing (P2/P2\\*), (3) Speichersicherheit bei Parser-Code, der fremde Bytes von fremden Steuergeräten verarbeitet. Bindung über **UniFFI** (stabile Kotlin-/Swift-APIs, kein JNI-Handwerk). Die J2534-DLL bleibt davon getrennt in C, weil sie in fremden Prozessen läuft — Begründung, Folgen und Aufwand: [ADR-001](adr-001-kernsprache.md).
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
