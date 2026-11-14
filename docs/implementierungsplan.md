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

## 2. Technologientscheidungen (mit Gegenargument)

| Entscheidung | Begründung | Was dagegen spricht |
|---|---|---|
**Rust-Kern für die App** | Ein Protokollstack für Android und iOS; deterministisches ISO-TP-Timing; speichersicher bei fremden Bytes von fremden Steuergeräten | Rust-Kompetenz ist teuer und rar. Fällt die Personalentscheidung dagegen aus, greift Kotlin Multiplatform mit derselben Modulgrenze ([ADR-001](adr-001-kernsprache.md) §7) |
**J2534-DLL bleibt C** | Sie läuft in fremden Prozessen (ODIS, ISTA), ein Panic über die C-Grenze wäre undefiniertes Verhalten; die C-Fassung ist getestet und funktioniert | Zwei Protokollimplementierungen (C und Rust) — aufgefangen durch einen Konformitätskorpus aus den C-Tests ([ADR-001](adr-001-kernsprache.md) §7.4) |
