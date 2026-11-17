# Implementierungsplan — Code

**Stand:** 14.11.2026 · Grundlage: [werkstatt-app-plan.md](werkstatt-app-plan.md) §4 · [wirtschaftlichkeitsrechnung.md](wirtschaftlichkeitsrechnung.md)
**Ziel:** aus dem Plan ein Repository machen, in dem die Reihenfolge der Arbeit klar ist — nicht ein Wunschzettel an Architektur.

---

## 1. Repository-Struktur

```
app-android/      Kotlin + Compose, Tablet-first, minSdk 29
app-ios/          Swift + SwiftUI, iPad- und iPhone-Layouts
core/             Rust: Protokollstack, Funktions-Runtime, Content, Audit, Checker
core-ffi/         UniFFI-Bindings für Kotlin und Swift (eine IDL, zwei Sprachen)
j2534/            J2534-PassThru-Port (C): Windows-DLL, Linux/macOS-Referenz  ← vorhanden, eingefroren
tools/            Content-Editor, Signierer, Log-Analyzer, VCI-Checker
content/          Funktionsdefinitionen (YAML), Evidence-Logs, Freigabestatus
model/            Wirtschaftlichkeitsmodell (Python)                        ← vorhanden
docs/             Planung, Spezifikationen                                  ← vorhanden
```

**Regel:** `core/` kennt weder UI noch Plattform. `app-*` enthält keine Protokoll-Logik. `j2534/` enthält keine Fahrzeuglogik — nur Transport und ABI. Die Grenze verläuft entlang des Prozesses: `j2534/` bedient Windows und fremde Software, `core/` bedient die App. Begründung und Folgen: [ADR-001](adr-001-kernsprache.md).

---

## 2. Technologieentscheidungen (mit Gegenargument)

| Entscheidung | Begründung | Was dagegen spricht |
|---|---|---|
**Rust-Kern für die App** | Ein Protokollstack für Android und iOS; deterministisches ISO-TP-Timing; speichersicher bei fremden Bytes von fremden Steuergeräten | Rust-Kompetenz ist teuer und rar. Fällt die Personalentscheidung dagegen aus, greift Kotlin Multiplatform mit derselben Modulgrenze ([ADR-001](adr-001-kernsprache.md) §7) |
**J2534-DLL bleibt C** | Sie läuft in fremden Prozessen (ODIS, ISTA), ein Panic über die C-Grenze wäre undefiniertes Verhalten; die C-Fassung ist getestet und funktioniert | Zwei Protokollimplementierungen (C und Rust) — aufgefangen durch einen Konformitätskorpus aus den C-Tests ([ADR-001](adr-001-kernsprache.md) §3.4) |
**Nativ (Compose / SwiftUI) statt Cross-UI** | BLE-Stack, USB-Host, Dateisystem, Split-View, Stift-Signatur, Handschuh-/Sonnenlicht-Themes | Zwei Codebasen für UI. Bewusster Preis für Werkstatt-Robustheit |
**UniFFI statt handgeschriebenem JNI** | Eine IDL, generierte Kotlin- und Swift-Bindings, Fehler als Exceptions/`throws`, `async` über die Grenze; JNI wäre die dritte handgepflegte Grenzfläche neben iOS-Bridging und DLL-Exporten | Zusätzliche Build-Stufe; UniFFI ist vor 1.0 (0.32.x) — Version pinnen, Bindings nur in der CI erzeugen |
**Datengetriebene Funktionen (YAML) statt Code je Marke** | 300 Funktionen × 40 Marken sind ohne Datenformat nicht pflegbar | Braucht ein frühes, stabiles Schema — deshalb Sprint 1 |
**J2534 als eigene DLL** | Eine API für alle VCIs, OEM-Software kann unsere Hardware nutzen, testbar ohne Fahrzeug | Siehe [j2534-dll-spezifikation.md](j2534-dll-spezifikation.md) — Aufwand und Validierung sind nicht trivial |

---

## 3. Module des Kerns

Reihenfolge = Abhängigkeitsreihenfolge. Jedes Modul hat eine eigene Testsuite ohne Fahrzeug (Loopback/Simulator).

```rust
// core/src/vci.rs — die einzige Stelle, die Hardware kennt
pub trait Transport: Send + Sync {
    fn open(&mut self) -> Result<()>;
    fn close(&mut self) -> Result<()>;
    fn configure(&mut self, cfg: &ChannelConfig) -> Result<()>;
    fn send(&mut self, frame: &CanFrame) -> Result<()>;
    fn recv(&mut self, timeout: Duration) -> Result<Option<CanFrame>>;
    fn vbat_mv(&mut self) -> Result<u32>;
    fn capabilities(&self) -> Capabilities;   // CAN FD? DoIP? K-Line? Pins?
}

// core/src/isotp.rs — ISO 15765-2, das Herzstück
pub struct IsotpChannel { /* bs, stmin, addressing, padding, p2 */ }
impl IsotpChannel {
    pub fn send(&mut self, payload: &[u8], t: &mut dyn Transport) -> Result<()>;
    pub fn recv(&mut self, t: &mut dyn Transport, timeout: Duration) -> Result<Vec<u8>>;
}

// core/src/uds.rs — ISO 14229-1
pub struct UdsClient<'a> { /* session state, p2/p2_star, tester present */ }
impl<'a> UdsClient<'a> {
    pub fn session(&mut self, kind: SessionType) -> Result<()>;
    pub fn read_did(&mut self, did: u16) -> Result<Vec<u8>>;
    pub fn read_dtc(&mut self) -> Result<Vec<Dtc>>;
    pub fn routine(&mut self, id: u16, ctrl: RoutineControl, data: &[u8]) -> Result<Vec<u8>>;
    pub fn security_access(&mut self, level: u8, p: &dyn SecurityProvider) -> Result<()>;
}

// core/src/function.rs — die Werkstattfunktion als Daten
pub struct FunctionDef {
    pub id: String, pub risk: Risk,          // R0..R4
    pub match_rule: VehicleMatch,            // VIN-Muster, HSN/TSN, SG-IDs
    pub preconditions: Vec<Precondition>,    // Klemme 15, Spannung, Motor aus
    pub steps: Vec<Step>,                    // Request/Expect/Timeout/Recovery
    pub backup: Option<BackupSpec>,          // Voll-Read vor Write
    pub provenance: Provenance,              // Quelle, Freigabe, Evidence-Hash
}
```

**Sicherheitsregel im Code:** `SecurityProvider` ist ein Trait mit leerer Standardimplementierung. Seed/Key-Algorithmen liegen **nicht** im Repository, sondern kommen als signierter, lizenzierter Provider — sonst entsteht genau das Produkt, das der Plan in §1.1 ausschließt.

**Audit:** jede Schreiboperation erzeugt einen Eintrag mit Hash-Kette (`core/src/audit.rs`). Ohne Audit-Eintrag kein erfolgreicher Abschluss — das ist die Grundlage für SERMI-Prüfungen und Haftung.

---

## 4. Sprintfolge für Version 1 (Service-Fokus)

10 Arbeitstage je Sprint, geschätzt in Personentagen (PT). Gesamt ≈ 1.320 PT — das entspricht den 792 T€ Personalkosten der V1 im Rechenmodell (600 €/PT Vollkosten).

| Sprint | Inhalt | Ergebnis | PT |
|---|---|---|---|
S1 | Content-Schema (YAML) + Validator + 5 Beispiel-Funktionen; `vci.rs` + `Transport`-Trait + Loopback | Schema steht, erste Funktionen testbar | 60
S2 | `isotp.rs` vollständig (Single/First/Consecutive/FlowControl, Extended Addressing, Padding, Timeouts) + Fuzz-Ziel | ISO-TP gegen Simulator und 2 echte Fahrzeuge | 90
S3 | `uds.rs`: 0x10/0x11/0x14/0x19/0x22/0x27/0x2E/0x31/0x3E, NRC-Handling inkl. 0x78 | DTC, Live-Daten, Routinen | 100
S4 | `function.rs` (Preconditions/Steps/Recovery) + 10 Service-Funktionen | 10 Rückstellungen lauffähig | 90
S5 | J2534-Port auf echter Hardware (WinUSB) + Registry + VCI-Checker | ein Passthru-Gerät nutzbar | 80
S6 | Android-App: Fahrzeugidentifikation, Diagnose, Funktionsausführung, Audit, PDF | Feldtest-version Android | 140
S7 | iOS-App: gleiche Flows, BLE-Transport, iPad-Layouts | Feldtest-Version iOS | 140
S8 | Backup/Restore, Doku-Export, Rollen/Rechte, Offline-Sync | Pilotfähig | 110
S9 | Content auf 10 Modelle ausrollen (Content-Team, parallel ab S4) | 10 Modelle × 10 Funktionen | 180
S10 | Beta bei 3–5 Werkstätten, Shadow-Mode, Fehlerbehebung | Launch-Entscheidung | 130
S11 | Härtung: Spannungsabfall, Hot-Plug, Abbrüche mitten im Ablauf | Release-Kandidat | 90
S12 | Backend-Reife: Lizenzen, Content-CDN, Signierung, Kill-Switch | Betriebsfähig | 110

**Parallel, nicht verhandelbar:** Rechtsgutachten (M0–M2) und VCI-Partnerverträge laufen ab Tag 1, sonst blockieren sie S5.

---

## 5. Was Version 1 bewusst nicht enthält

- **Kein Flashen, keine Programmier-Spannung.** `PassThruSetProgrammingVoltage` antwortet heute `ERR_NOT_SUPPORTED` — bewusst, weil eine falsche Spannung am falschen Pin ein Steuergerät zerstört.
- **Keine Codierung (R2/R3)** — erst nach Backup/Restore-Nachweis auf allen Pilotmodellen.
- **Kein SERMI-Modul** — das ist ein Berechtigungs- und Prüfprozess, kein Feature.
- **Kein periodisches Senden** (`PassThruStartPeriodicMsg`) — wird erst gebraucht, wenn ein Fahrzeug es verlangt.

---

## 6. Definition of Done je Modul

Ein Modul gilt als fertig, wenn **alle** Punkte erfüllt sind:

1. Unit-Tests ohne Hardware (Loopback) laufen im CI.
2. Ein HIL-Test gegen mindestens ein echtes Steuergerät ist dokumentiert (Log als Evidence im Repo).
3. Fehlerpfade getestet: Timeout, NRC, Busabbruch, Stromausfall mitten im Schreiben.
4. ~~Fuzzing mindestens 10 Minuten ohne Absturz (Parser-Code).~~ Erfüllt für den Parser-Code (ISO-TP, UDS) am 17.11.2026: `sh scripts/fuzz.sh`, 604 s, 33,5 Mio. und 55,6 Mio. Läufe, kein Absturz ([ci.md](ci.md)). Für neue Module gilt der Punkt unverändert.
5. Öffentliche API dokumentiert (rustdoc), keine `unwrap()` in Pfaden, die fremde Daten verarbeiten.
6. Audit-Eintrag vorhanden und exportierbar.
7. Risikoklasse zugeordnet und in der Freigabematrix eingetragen.

---

## 7. CI/CD und Werkzeuge

| Baustein | Inhalt |
|---|---|
Build-Matrix | Rust für `aarch64-linux-android` und `aarch64-apple-ios` (`x86_64-pc-windows-msvc` erst für einen Desktop-Worker); C für Windows (MSVC) und Linux (gcc) — die DLL bleibt C, siehe [ADR-001](adr-001-kernsprache.md) |
Kern-Jobs | `cargo test` (209 Tests), `cargo clippy --all-targets -- -D warnings` und `cargo fmt --check` für Kern, FFI und Fuzz laufen je als eigener Job; der Reformat der acht handformatierten Dateien ist übertragen und jede gegen `git hash-object` geprüft ([ci.md](ci.md)); `cargo deny` (Lizenzen, CVEs) fehlt ganz |
C-Jobs | `gcc -Wall -Wextra -Wpedantic`, Exportliste gegen `win/j2534.def` prüfen (wie lokal: 16 Symbole) |
Fuzzing | `sh scripts/fuzz.sh` — `cargo-fuzz` und nightly, zwei Ziele (ISO-TP, UDS), 300 s je Ziel wie in §6.4; ein CI-Job dafür fehlt, solange die Jobs nicht starten ([ci.md](ci.md)) |
HIL | nächtlich am Prüfstand: 20 Funktionen × 3 Steuergeräte, Golden-Log-Vergleich |
Artefakte | signierte Builds, SBOM (CycloneDX), reproduzierbar (`--locked`, feste Toolchain) |
Release | DLL mit EV-Zertifikat signieren, Hash im Content-Manifest, Rollback-Paket |

---

## 8. Teamzuordnung (V1, aus dem Rechenmodell)

| Rolle | FTE | Module |
|---|---|---|
Rust/Protokoll | 1,5 | `vci`, `isotp`, `uds`, `function`, `audit` |
Android | 1,5 | `app-android`, UniFFI-Bindung |
iOS | 1,5 | `app-ios`, UniFFI-Bindung |
Content | 0,5 → 1,5 | `content/`, Schema, Funktionen, Evidence |
Kfz-Meister | 0,4 | fachliche Freigabe, Prüfstandsarbeit |
QA/HIL | 0,5 | Prüfstand, Golden-Logs, Beta |
Backend | 0,5 | Lizenzen, Content-Auslieferung |
PM | 1,0 | Sprintsteuerung, Partner, Recht |

---

## 9. Risiken im Code (und was sie früh sichtbar macht)

| Risiko | Frühindikator | Gegenmaßnahme |
|---|---|---|
ISO-TP-Timing bricht bei schnellen Gateways | Sprint 2 am Fahrzeug statt nur im Simulator | Zeitmessung im CI, P2/P2\*-Parameter je Fahrzeug |
Content-Schema passt nicht zu echten Funktionen | Sprint 1 mit 5 echten Funktionen, nicht mit Spielwiese | Schema gegen 30 Funktionen aus dem Katalog prüfen |
VCI-Vielfalt explodiert im Support | jeder neue Dongle braucht einen Testfall | Capability-Modell, VCI-Checker, Matrix mit Reifegrad |
iOS blockiert USB-Passthru | früh im S7 | nur BLE/WLAN/IP auf iOS, Gateway für USB |
Seed/Key-Anforderung kommt zu spät | Pilotwerkstatt fragt in Woche 2 danach | Provider-Trait von Anfang an, lizenzierter Weg dokumentiert |

---

## 10. Erste drei Aufgaben (diese Woche)

1. `core/`-Skelett mit `Transport`-Trait und Loopback; die 165 C-Tests aus `j2534/` als Konformitätskorpus exportieren, damit der Rust-ISO-TP gegen dieselben Bytes läuft.
2. Content-Schema v0.1 mit 5 echten Service-Funktionen aus dem Katalog (Ölservice, EPB, DPF, Batterie, Lenkwinkel).
3. J2534-Port auf WinUSB umstellen und mit einem echten Gerät (Tactrix OpenPort oder CarDAQ) im Loopback gegen den Simulator testen.
