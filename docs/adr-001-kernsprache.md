# ADR-001: Kernsprache für die App — Rust mit UniFFI, C bleibt für die J2534-DLL

**Status:** entschieden · **Datum:** 14.11.2026 · **Gültig bis:** Überprüfung nach Sprint 1 (§7)
**Betrifft:** [implementierungsplan.md](implementierungsplan.md) §1–§4, [werkstatt-app-plan.md](werkstatt-app-plan.md) §4.1

---

## 1. Kontext

Es gibt zwei Verbraucher, die oft in einen Topf geworfen werden, aber völlig verschiedene Lebensräume haben:

| Verbraucher | Läuft in | Sprachen | Anforderung |
|---|---|---|---|
| **App** (Android, iOS) | eigener Prozess, eigene UI | Kotlin, Swift | Protokollstack, Funktionslogik, Audit — eine Implementierung für beide |
| **J2534-DLL** | **fremden** Prozessen (ODIS, ISTA, Techstream) | C-ABI | 16 Exporte, keine Panics, kein UI, wird von Software geladen, die uns nicht kennt |

Vorhanden ist heute der zweite Teil: `j2534/` mit 4.581 Zeilen C, 159 grünen Tests, gegen ein simuliertes Gerät verifiziert. Der erste Teil — der App-Kern — existiert noch nicht.

Die Frage lautet also nicht „C oder Rust", sondern **„welche Sprache für den App-Kern, und bleibt die DLL, wo sie ist?"**

---

## 2. Optionen

### Option A — C plus handgeschriebenes JNI/Swift-Bridging

Der vorhandene C-Code wird zum App-Kern; Kotlin spricht über JNI damit, Swift über einen bridging header.

| | |
|---|---|
| Vorteile | Kein Neuschreiben. Kleinste Binärgröße. Kein zusätzliches Toolchain. Ein Entwickler mit C-Kenntnissen kommt weit. |
| Nachteile | **Drei** handgepflegte Grenzflächen (JNI für Android, C-Header für iOS, DLL-Exporte für Windows). JNI-Handwerk ist die klassische Fehlerquelle: lokale Referenzen, Exceptions, `AttachCurrentThread`, UTF-8/UTF-16. Kein async — lange UDS-Sequenzen brauchen Callbacks oder blockierende Threads. Fehler werden zu Rückgabecodes, die jede Seite einzeln auswertet. Und: die Parser (Framing, ISO-TP-Reassembly, UDS) verarbeiten **Bytes von fremden Steuergeräten** — genau der Code, in dem Speicherfehler teuer werden. |

### Option B — Rust-Kern mit UniFFI, C-DLL bleibt C *(gewählt)*

Der App-Kern entsteht in Rust; UniFFI erzeugt Kotlin- und Swift-Bindings aus einer IDL. Die J2534-DLL bleibt unverändert in C.

| | |
|---|---|
| Vorteile | **Eine** Implementierung für Android und iOS, **null** handgeschriebene Bindings — UniFFI generiert Kotlin und Swift aus derselben Beschreibung. Fehler werden zu Kotlin-Exceptions bzw. Swift-`throws`, nicht zu Codes. `async` wird über die Sprachgrenze abgebildet (`suspend fun` / `async`), was für UDS-Sequenzen mit Tester-Present und Abbruch genau passt. Speichersicherheit im Parser-Code, den fremde Bytes erreichen. Die DLL bleibt unangetastet — kein Risiko für Software, die sie schon lädt. |
| Nachteile | Rust-Kompetenz muss vorhanden sein (118 T€/FTE-Jahr im Rechenmodell, ohnehin eingeplant). UniFFI ist **vor 1.0** (0.32.x, mit Breaking Changes zwischen Minor-Versionen) — Version pinnen, Bindings in der CI erzeugen, nie von Hand ändern. Zusätzliche Build-Stufe (cargo-ndk, xcframework). |

### Option C — Rust exportiert auch die J2534-DLL *(verworfen)*

Verlockend, weil „eine Implementierung für alles". Verworfen, weil der Gewinn klein und das Risiko real ist: die DLL wird in **fremde Prozesse** geladen, ein Panic über eine C-Grenze ist undefiniertes Verhalten, und die C-Fassung ist bereits getestet und funktioniert. Ein Rewrite würde Monate kosten und null Funktion hinzufügen. Die DLL ist eine Vertriebsfläche, kein Architekturprojekt.

---

## 3. Entscheidung

1. **Der App-Kern wird in Rust geschrieben** und über **UniFFI** an Kotlin und Swift gebunden. Kein handgeschriebenes JNI.
2. **Die J2534-DLL bleibt C.** Sie wird nicht nach Rust portiert. Sie ist eingefroren: Bugfixes ja, neue Funktionen nur noch im Rust-Kern.
3. **Die Grenze verläuft entlang des Prozesses, nicht entlang der Sprache:** `j2534/` bedient Windows und fremde Software; `core/` bedient die App auf Android, iOS und (später) im Hintergrunddienst.
4. **Die C-Tests werden zum Konformitätskorpus.** Framing und ISO-TP existieren danach zweimal; die 159 C-Prüfungen werden als Golden-Logs exportiert, und der Rust-ISO-TP muss dieselben Bytes erzeugen und dieselben Fehler erkennen. Damit ist die Doppelimplementierung kontrolliert statt zufällig.

---

## 4. Begründung in fünf Sätzen

Der teuerste Fehler in diesem Produkt wäre ein Protokollstack, der auf Android anders tickt als auf iOS — UniFFI macht daraus eine Implementierung. Die Bytes, die wir parsen, kommen von Steuergeräten, die wir nicht kontrollieren; Rust prüft das zur Compile-Zeit, C erst im Feld. JNI wäre die dritte handgepflegte Grenzfläche neben iOS-Bridging und DLL-Exporten, und genau dort entstehen die Fehler, die niemand reproduzieren kann. `async` über die Sprachgrenze ist bei UDS-Sequenzen kein Luxus, sondern die Rettung vor blockierten UI-Threads. Und die DLL bleibt C, weil sie in Prozessen läuft, die uns nicht gehören — dort ist „funktioniert seit gestern" mehr wert als „moderner".

---

## 5. Folgen

### Repository

```
core/          Rust: vci (Transport-Trait), isotp, uds, function, audit, checker
core-ffi/      UniFFI-IDL + generierte Bindings (Kotlin, Swift) — generiert, nicht editiert
app-android/   Kotlin + Compose, konsumiert core-ffi
app-ios/       Swift + SwiftUI, konsumiert core-ffi
j2534/         C: Windows-DLL + Desktop-Transport  ← eingefroren, bleibt wie es ist
```

`core/` enthält keine Plattform-APIs; jeder Transport (BLE, USB, J2534-DLL) implementiert das `Transport`-Trait.

### Aufwand

| Posten | PT | Anmerkung |
|---|---|---|
| Rust-Kern S1–S4 | 340 | unverändert aus dem Plan — der C-Code war nie der App-Kern, es wird nichts „umgeschrieben" |
| Konformitätskorpus aus den C-Tests | 10 | Golden-Logs erzeugen, Rust-ISO-TP dagegen laufen lassen |
| Checker nach `core/` portieren | 8 | ~350 Zeilen + 34 Tests als Spezifikation; C-Fassung bleibt Referenz |
| **Summe zusätzlich** | **18** | V1 damit ≈ 1.338 PT statt 1.320 |

Der wichtigste Punkt: **es gibt keinen Portierungsaufwand für den vorhandenen C-Code**, weil er eine andere Aufgabe hat. Das war der Grund, Option C zu verwerfen.

### Technische Auflagen

1. **UniFFI-Version pinnen** (`=0.32.x`), Bindings ausschließlich in der CI erzeugen, Upgrades nur an Sprint-Grenzen mit vollem Testlauf.
2. **Ein Besitzer je Sitzung:** der UDS-Client ist nicht `Sync`; die App bekommt eine Fassade mit `Mutex` — zwei gleichzeitige Zugriffe auf denselben Bus sind ein Hardwarefehler, kein Wettlauf.
3. **Kein Panic über die Grenze:** UniFFI fängt Panics und bildet sie auf Fehler ab; zusätzlich `panic = "abort"` im Release-Profil, damit ein Panic nicht durch fremden Code läuft.
4. **Abbruch ist Teil der API:** jede lange Operation bekommt ein Abbruchsignal, sonst hängt die Werkstatt vor einem blockierten Bildschirm.
5. **Der C-Korpus bleibt liegen**, auch wenn `core/` ihn überholt — er ist der einzige unabhängige Zeuge für das Wire-Format.

### Risiken

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| UniFFI-Breaking-Change | Bindings brechen beim Upgrade | Version pinnen, Upgrade nur an Sprint-Grenzen, generierte Dateien nicht anfassen |
| Keine Rust-Kompetenz verfügbar | S1 startet nicht | **Entscheidungspunkt Ende S1**: ist kein Rust-Entwickler an Bord, wird der Kern auf Kotlin Multiplatform umgestellt (Transport bleibt C). Diese Entscheidung wird *einmal* getroffen, nicht schleichend |
| Zwei ISO-TP-Implementierungen driften | Unterschiedliches Verhalten App vs. OEM-Tool | Konformitätskorpus im CI, `j2534/` eingefroren |
| Build-Komplexität (NDK, Xcode, MSVC) | Lange CI, schwer reproduzierbar | Build-Matrix aus §7 des Plans, Container mit gepinnten Toolchains |

---

## 6. Was ausdrücklich verworfen wird

- **Handgeschriebenes JNI als Dauerlösung.** Zulässig nur als Wegwerf-Spike, wenn vor dem Rust-Kern ein Bildschirm gezeigt werden muss — dann mit dem Vermerk, dass er ersetzt wird.
- **Rust in der DLL.** Siehe Option C.
- **Kotlin Multiplatform als Standardweg.** Bleibt der Plan B, falls die Personalentscheidung gegen Rust fällt — nicht die stille Alternative.

---

## 7. Überprüfung

Diese Entscheidung wird **einmal** überprüft, am Ende von Sprint 1, an drei Fragen:

1. Steht ein Rust-Entwickler zur Verfügung (Festanstellung oder Vertrag)?
2. Erzeugt `cargo ndk` + UniFFI eine Kotlin-Bindung, die auf einem echten Gerät läuft?
3. Läuft der Rust-ISO-TP gegen den Konformitätskorpus grün?

Fällt eine der drei Antworten negativ aus, greift der Wechsel auf Kotlin Multiplatform — mit derselben Modulgrenze, damit der Transport und die Content-Schicht unverändert bleiben.
