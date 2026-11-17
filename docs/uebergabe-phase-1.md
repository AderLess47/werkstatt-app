# Übergabe — Zwischenschritt und Plan Phase 1 (M1)

**Für:** den nächsten Agenten („Gemini Argon 4“), der ohne den vorherigen Chat
weiterarbeiten soll.
**Stand:** 17.11.2026 · **Repo:** `AderLess47/werkstatt-app` (privat), Branch
`main`, letzter Commit `74cf437`.
**Aufbau:** Teil A sagt, wo das Projekt steht — belegt und unbelegt getrennt.
Teil B ist der Plan für Phase 1 = **Meilenstein M1 („Diagnose-Basis“)** aus
[werkstatt-app-plan.md](werkstatt-app-plan.md) §8, zerlegt in Arbeitspakete, die
in dieser Umgebung tatsächlich machbar sind.

Teil A ist die Grundlage: wer ihn überspringt, baut auf Annahmen, die hier
ausdrücklich als ungeprüft markiert sind.

---

# A. Zwischenschritt

## A.1 Was existiert

Gemessen am 17.11.2026 im Arbeitsbaum (Zeilen und Dateien gezählt, nicht
geschätzt):

| Baustein | Stand | Umfang |
| --- | --- | --- |
| `core/src/transport.rs` | Transport-Trait, `CanFrame`, `Capabilities`, Fehlerarten — die einzige Stelle, die Hardware kennt | 100 Zeilen |
| `core/src/isotp.rs` | ISO 15765-2: Single/First/Consecutive/FlowControl, Blockgröße, `STmin`, Padding, Fristen | 445 Zeilen |
| `core/src/loopback.rs` | Transport im Speicher, damit alles ohne Gerät prüfbar ist | 66 Zeilen |
| `core/src/uds.rs` | allgemeiner Client: `10` (auch `10 03`), `11`, `14`, `19`, `22`, `31`, `3E`; NRC-Klartext, 0x78-Kette, `runde()` als gemeinsamer Pfad | 1.700 Zeilen |
| `core/src/j1979.rs` | Fehlercodes dekodieren: `P0301`, Statusbit als Wort, Fehlerart roh | 20 Tests |
| `core/src/sfd.rs` | VAG-SFD über UDS: `22 01 74`, `10 03`, `31 01 C0 04/08/09` (gebaut über `uds::routine_start_anfrage`), NRC-Klartext, 0x78-Aufschub, Token-Hex | 1.156 Zeilen |
| `core/src/content.rs` | Funktionsdefinitionen aus YAML lesen und prüfen, Schema, Fehler mit Datei und Zeile | 680 Zeilen |
| `core/src/function.rs` | Vorbedingungen, Schritte, Recovery, `VehicleMatch`, `Kontext` | 896 Zeilen |
| `core/src/audit.rs` | SHA-256-Hash-Kette über jede Operation, JSON-Export | 584 Zeilen |
| `content/` | `schema/funktion.schema.json` und fünf Funktionen in `funktionen/` | 6 Dateien |
| `core/fuzz/` | zwei Fuzz-Ziele: ISO-TP (Empfangs- und Sendeweg) und UDS-Parser | 2 Ziele |
| `core/tests/corpus.rs` + `corpus/` | Konformitätskorpus gegen die C-Implementierung | 4 Tests |
| `core-ffi/src/lib.rs` | UniFFI-Grenze: `CanTransport`, `IsoTp`, `Sfd`, `LoopbackTransport`, `SfdStatusFfi` | 648 Zeilen |
| `core-ffi/src/bin/uniffi_bindgen.rs` | Bindings erzeugen (Kotlin, Swift) | — |
| `j2534/` (C) | PassThru-Port, USB-Backend (WinUSB + Simulator), VCI-Checker, Rollout-Auswertung | 165 C-Prüfungen |
| `android/` | Compose-App: 13 Dateien im Wurzelpaket, 13 unter `transport/` — USB-Weg, OBDLink-Weg, BLE-Weg, SFD-Bildschirm, Berichte | 174 JVM-Tests |
| `model/` | Wirtschaftlichkeitsmodell (Python) | — |
| `docs/` | 14 Planungs- und Prüfdokumente | — |

**Was fehlt** (und was M1 gerade deshalb groß macht): `core/src/fahrzeug.rs`
(die Entscheidung „welches Fahrzeug ist das“, nicht das Lesen der VIN) sowie die
Restdienste von P1 — `0x27`, `0x2E`. Dazu Freeze Frames und Live-Daten
aus P2, `tools/`, `app-ios/`, Backend.

Am 16.11.2026 standen hier noch `uds.rs`, `function.rs`, `audit.rs` und
`content/` als fehlend. Das ist überholt; nachgezogen am 17.11.2026: `uds.rs`
(1.316 Zeilen, allgemeiner Client mit `runde()`, Session, DID, DTC, Reset,
Fehlerspeicher löschen, TesterPresent, NRC und 0x78-Kette), `function.rs` (896),
`content.rs` (680, Schema und Validator), `audit.rs` (584, Hash-Kette) sowie
`content/schema/funktion.schema.json` mit fünf Funktionen in
`content/funktionen/`. `core/src/lib.rs` führt sie als `pub mod`, nicht mehr als
„folgt“ — die Reihenfolge dort ist die Abhängigkeitsreihenfolge, nicht eine
Wunschliste.

Neu am 17.11.2026: `j1979.rs` mit der DTC-Dekodierung (20 Tests). Was dort
**nicht** steht, ist der Rest von P2: Freeze Frame (`19 04`) und Live-Daten aus
DID-Sätzen brauchen Antwortlängen, die noch niemand aufgezeichnet hat.

Ebenfalls offen: eine **Fahrzeugidentifikation**. Die VIN lässt sich lesen
(`22 F190` über `uds::UdsClient::read_did`, mit Test für die 20-Byte-Antwort),
und `VehicleMatch` prüft ein `vin_praefix` — was fehlt, ist die Stelle, die aus
einer Verbindung ein Fahrzeug macht (Kennungen, HSN/TSN, im Zweifel „unbekannt“
statt geraten).

## A.2 Was belegt ist

Alles hier ist am 16. und 17.11.2026 in dieser VM gelaufen, nicht abgeleitet:

| Prüfung | Befehl | Ergebnis |
| --- | --- | --- |
| C-Kern | `make -C j2534 test` | 165 `PASS`-Zeilen, „Alle Rollout-Prüfungen bestanden“ |
| Rust-Kern | `cargo test --locked --manifest-path core/Cargo.toml` | 200 Tests + 4 Korpus-Tests |
| FFI | `cargo test --locked --manifest-path core-ffi/Cargo.toml` | 15 Tests |
| App ohne Gerät | `JAVA_HOME=/tmp/jdk sh scripts/jvm-tests.sh` | `OK (174 tests)` |
| Android-Build | `./android/gradlew -p android :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Zahlen gegen Jobnamen | `sh scripts/zahlen-pruefen.sh` | alle fünf gleich, Exit 0 |
| Fuzzing (neu) | `sh scripts/fuzz.sh` | 604 s, 33,5 Mio. + 55,6 Mio. Läufe, kein Absturz |

## A.3 Was ausdrücklich **nicht** belegt ist

- **Keine Hardware.** Kein Dongle, kein Fahrzeug, kein `adb`, kein Emulator
  (`/dev/kvm` nicht zugänglich). Ungeprüft bleiben damit: jede USB-Strecke, jede
  BLE-Strecke, das `dlopen` der Android-`.so` zur Laufzeit, und **jede** der drei
  in M1 geforderten Gerätezertifizierungen.
- **Kein CI-Ergebnis.** 147 Läufe, **0 erfolgreich**; die Jobs bekommen keinen
  Runner (`runner_id: 0`, `steps: []`, Abrechnungssperre des Kontos). Wer im
  Repo „grün“ liest, liest einen Jobnamen, keine Messung — Einzelheiten in
  [ci.md](ci.md).
- **Keine Oberfläche gesehen.** Der SFD-Bildschirm kompiliert, wurde aber nie
  angezeigt. „Sieht gut aus“ ist hier keine zulässige Aussage.
- **Die Deutung der SFD-Statusbytes ist eine Annahme** (Byte 0 `00`/`01`, Byte 1
  Rolle, Byte 2–3 Minuten). Der Rohwert steht auf dem Bildschirm, damit die
  Annahme am Fahrzeug auffällt statt geglaubt zu werden.

## A.4 Offene technische Punkte

1. **29-Bit-Kennungen.** Das Drahtformat überträgt die CAN-Kennung als Wert,
   aber **ohne Extended-Flag**. Ob der Dongle daraus einen 29-Bit-Rahmen macht,
   entscheidet seine Firmware — nicht prüfbar ohne Fahrzeug mit bekannter
   29-Bit-Adresse.
2. **Der C-/J2534-Pfad ist auf zwei Kennungen festgelegt:**
   `CAN_REQ_ID 0x7E0` / `CAN_RSP_ID 0x7E8` (`j2534/src/transport_usb.c:47-48`),
   und `can_recv` **verwirft** jede Antwort mit anderer Kennung (`:257`). Die App
   benutzt diesen Pfad nicht (sie lädt nur `libvci_ffi.so`) — für M1 wird er aber
   relevant, sobald mehrere Steuergeräte über Passthru angesprochen werden.
3. **Der UDS-Client ist da, aber noch nicht vollständig.** `sfd.rs` läuft seit
   dem 17.11.2026 über [`crate::uds::UdsClient::runde`] und `uds::parse_sitzung`
   — einen zweiten NRC- und 0x78-Pfad gibt es nicht mehr. `uds.rs` kennt heute
   `0x10` (inklusive `10 03`), `0x11`, `0x14`, `0x19`, `0x22`, `0x31` und `0x3E`;
   aus M1 fehlen `0x27` und `0x2E`. `0x31` (RoutineControl, Start/Stop/Ergebnis)
   kam am 17.11.2026 dazu; `sfd.rs` baut seine `31`-Nachrichten seitdem mit
   [`crate::uds::routine_start_anfrage`] statt mit einer eigenen Bytefolge.
4. ~~**`docs/android-app-modul.md` nennt 119 `@Test`**~~ — nachgezogen: 174 in
   neunzehn Dateien (SFD, Funktions-Runtime und Audit-Kette kamen dazu).
5. **`docs/geraetepruefung.md` Abschnitt 10 (SFD)** ist geschrieben und nie
   gelaufen.
6. **Der lokale Git-Index ist unzuverlässig.** `git status` zeigt Dateien als
   geändert, die längst per API committet sind. Wahrheit ist die GitHub-API; ein
   lokaler `git log` endet bei `bcf6fc6` und ist **nicht** der Branch-Stand.

## A.5 Commits der letzten Runde (nachprüfbar)

| Commit | Inhalt |
| --- | --- |
| `1194861` | `core/src/sfd.rs` — Extended Session, `SfdStatus.roh`, 8 neue Tests |
| `cc24036` | `core-ffi/src/lib.rs` — `extended_session`, `sitzung`, `roh_hex` |
| `9592d99` | `SfdBedienung.kt` + `SfdBedienungTest.kt` — Ziel-IDs, Schrittfolge, Rohantwort |
| `987fa62` | `SfdAbschnitt.kt` — Zielauswahl, Schalter für `10 03`, Rohantwort-Karte |
| `e0ad560` | `docs/geraetepruefung.md` — Abschnitt 10 (SFD am Fahrzeug) |
| `b52440b` | `ci.yml` + `docs/ci.md` — aktuelle Testzahlen |
| `46a2c31` | `docs/ci.md` — Korrektur eines Anführungszeichens (Blob-Abgleich) |
| `db9d9f1` | `scripts/zahlen-pruefen.sh` + Hinweis in `docs/ci.md` |

Jeder dieser Commits wurde über die GitHub-API gesetzt und danach mit
`git hash-object <pfad>` gegen das zurückgemeldete `path_status.blob_sha`
verglichen. Nicht verglichen heißt hier: nicht committet.

## A.6 Arbeitsweise in diesem Repo (verbindlich)

- **Sprache:** Deutsch, auch in Kommentaren. Kommentare erklären das *warum*,
  nicht das *was*.
- **Keine Attribution:** keine „Generated with“, keine Co-Authored-By-Zeile.
- **Upload nur über die GitHub-Integration.** Kein Git-Remote, kein Token in der
  VM. Die API überträgt nur Inhalt (keinen Dateimodus) — Skripte werden deshalb
  als `sh scripts/…` aufgerufen.
- **Byte-Identität prüfen:** nach jedem Commit `git hash-object` gegen
  `blob_sha`. Weicht es ab, ist die *lokale* Datei maßgeblich, nicht der Commit —
  den Text aus dem Lauf übernehmen, nicht aus dem Gedächtnis tippen. (Genau
  daran ist `46a2c31` entstanden.)
- **Nichts erfinden.** Kein Geräte-, Dongle- oder Fahrzeugergebnis, das nicht
  gelaufen ist. „Nicht geprüft“ ist eine vollständige Antwort.
- **Zahlen nachziehen:** nach jeder Teständerung `sh scripts/zahlen-pruefen.sh`
  laufen lassen und die Zahlen in `.github/workflows/ci.yml` **und**
  `docs/ci.md` nachziehen. Das Skript bricht sonst ab — das ist gewollt.
- **Umgebung:** `export PATH=$HOME/.cargo/bin:$PATH`, `JAVA_HOME=/tmp/jdk`
  (ohne das bricht Gradle mit „JAVA_HOME is not set“ ab). Bindings unter
  `core-ffi/bindings/` sind gitignored und werden mit
  `./scripts/gen-bindings.sh kotlin` erzeugt.

---

# B. Plan Phase 1 — Meilenstein M1 („Diagnose-Basis“)

## B.0 Was M1 im Plan ist

> **M1 Diagnose-Basis** (Monat 2–8): Rust-Core (`isotp`, `uds`, `j1979`,
> `vci-hal`), Android- und iOS-App, BLE-VCI, Fahrzeugidentifikation,
> DTC/Live/Freeze-Frame, Audit, Auftragsdoku, 3 VCIs zertifiziert,
> 10 Service-Funktionen auf 10 Modellen.
> **Exit:** Feldtest bei Pilotwerkstätten ohne Datenverlust; Absturzrate < 0,5 %.

Im [Implementierungsplan](implementierungsplan.md) §4 entspricht das den Sprints
S1–S6, mit dem iOS-Anteil aus S7 und dem Content-Ausrollen aus S9.

**Ehrliche Einordnung vorweg:** M1 ist als Meilenstein ein halbes Jahr Arbeit
mehrerer Vollzeitkräfte — nicht ein Durchlauf. Von den oben genannten Punkten
sind in dieser Umgebung **ohne Hardware** erreichbar: `uds`, `j1979`,
Fahrzeugidentifikation, Content-Schema, Funktions-Runtime, Audit, FFI, Teile der
Android-Oberfläche. **Nicht** erreichbar: Gerätezertifizierung, Feldtest,
Absturzrate, iOS (kein macOS), BLE-Erprobung. Der Plan unten trennt das
konsequent, statt es später als „fast fertig“ zu verkaufen.

## B.1 Arbeitspakete in Abhängigkeitsreihenfolge

### P1 — `core/src/uds.rs`: allgemeiner UDS-Client

**Ziel:** die Dienste aus ISO 14229-1, die M1 braucht, als eigener Client über
`IsotpChannel` — nicht als Teil von `sfd.rs`.

**Umfang:** `0x10` (Session, inkl. `P2`/`P2*` aus der Antwort), `0x11` (Reset),
`0x14` (ClearDTC), `0x19` (ReadDTC: 02/04/06/0A), `0x22` (ReadDataByIdentifier),
`0x27` (SecurityAccess gegen ein `SecurityProvider`-Trait, **ohne**
Seed/Key-Algorithmen im Repo), `0x2E` (Write), `0x31` (RoutineControl), `0x3E`
(TesterPresent) — jeweils mit NRC-Behandlung, 0x78-Kette und
`suppressPosRspMsgIndicationBit`.

**Wichtig:** der 0x78- und NRC-Pfad existiert zweimal, wenn P1 nichts
konsolidiert. Ziel ist **ein** `runde()` in `uds.rs`; `sfd.rs` ruft ihn auf und
behält seine Tests als Regressionsnetz (seinerzeit 48 Tests, heute 200 — sie
mussten beim Umbau unverändert grün bleiben, ohne Änderung an den Erwartungen).

**Tests ohne Gerät:** je Dienst positiv / NRC / zu kurz / falscher Echo / 0x78
mit Folgeantwort / 0x78-Kette über drei Aufschübe hinaus. Zielgröße: ≥ 60 neue
Tests, alle gegen `Loopback`.

**Fertig, wenn:** `cargo test core` grün, `sfd.rs` unverändert grün, und keine
`unwrap()` auf Pfaden, die fremde Bytes verarbeiten.

### P2 — `core/src/j1979.rs`: DTC und Live-Daten auswerten

**Ziel:** reine Parser, keine Hardware.

**Stand 17.11.2026:** die DTC-Dekodierung steht (`system`, `code_text`,
`status_flags`, `unbelegte_bits`, `dekodiere_liste`, `dekodiere_roh`) mit 20
Tests. Offen: Freeze Frame und Live-Daten über DID-Sätze.

**Umfang:** DTC-Dreiergruppen (`P/C/B/U`, zwei Fehlerbits, Statusbyte mit
`testFailed`/`confirmedDtc`/`pendingDtc`), Freeze Frame (`0x19 04`), Live-Daten
über DID-Sätze. Unbekannte Bits bleiben stehen und werden als unbekannt
gekennzeichnet — kein Raten, wie schon bei `nrc_text`.

**Tests:** bekannte DTC-Beispiele je Buchstabe, abgeschnittene Antworten,
unbekannte Statusbits, leere DTC-Liste.

### P3 — `core/src/fahrzeug.rs`: Fahrzeugidentifikation

**Ziel:** entscheiden, ob ein Fahrzeug zu einer Funktionsdefinition passt.

**Umfang:** VIN über `22 F190` (17 Zeichen, Prüfziffer nach ISO 3779 optional —
nur wenn belegt), Steuergeräteliste über die vorhandenen Kennungen, HSN/TSN
**nur** aus einer belegten Quelle. `VehicleMatch` als Datenstruktur, damit P5
darauf aufsetzen kann.

**Falle:** HSN/TSN werden in diesem Projekt gern „aus dem Bauch“ ergänzt. Ein
falscher Treffer führt später zu einer Funktion am falschen Fahrzeug — lieber
`unbekannt` zurückgeben.

### P4 — Content-Schema und Validator *(Sprint S1 des Plans)*

**Ziel:** Werkstattfunktionen als Daten, nicht als Code je Marke.

**Umfang:** `content/schema/funktion.schema.json`, fünf Beispielfunktionen als
YAML, `core/src/content.rs` (laden, validieren, Fehler mit Datei und Zeile),
`core/src/bin/validieren.rs`. Pflichtfelder: `id`, `risk` (R0–R4),
`provenance` (Quelle, Freigabestufe, Evidence-Hash), `preconditions`, `steps`.

**Fertig, wenn:** der Validator eine Funktion ohne `provenance`-Hash ablehnt —
mit Test, nicht nur mit Zusage.

### P5 — `core/src/function.rs` und `core/src/audit.rs`

**Ziel:** die Funktion ausführen, nicht nur beschreiben; jede Schreiboperation
in einer Hash-Kette festhalten.

**Umfang:** `Step { request, expect, timeout, recovery }`, Vorbedingungen
(Klemme 15, Bordspannung, Motor aus), Backup-vor-Write, Abbruch mit Recovery,
Risikoklasse im Ablauf durchgesetzt. Audit: SHA-256 über Vorgängereintrag +
Eintrag, Export als JSON, ohne Audit kein erfolgreicher Abschluss.

**Tests:** Abbruch mitten im Ablauf, Recovery schlägt fehl, Audit-Kette nach
Manipulation eines Eintrags gebrochen, Spannungsabfall als Vorbedingung.

### P6 — FFI und Kotlin nachziehen

**Umfang:** neue Objekte über die Grenze (`Uds`, `Fahrzeug`, `Dtc`, `Funktion`,
`Audit`), `u8`-Felder als `u32` (UniFFI bildet `u8` als `UByte` ab — das ist
schon einmal Umweg gewesen), Bindings neu erzeugen, JVM-Tests ergänzen.

**Danach Pflicht:** `sh scripts/zahlen-pruefen.sh`, Zahlen in `ci.yml` und
`docs/ci.md` nachziehen (Jobnamen `Rust-Kern (…)`, `JVM-Tests ohne Gerät (…)`).

### P7 — Android-Oberfläche

**Umfang:** Fahrzeugidentifikation, DTC-Liste mit Statuswort, Live-Daten,
Funktionsausführung mit Risiko-Kennzeichnung, Auftragsdoku (die Berichts- und
Teilen-Bausteine existieren bereits). Projektkonvention: Status **als Wort**
neben der Farbe, Rohwerte immer sichtbar, Busaufrufe über eine Sperre gegen
Doppelklick auf `Dispatchers.IO`.

### P8 — Geräte und Feldtest *(nur mit Hardware)*

3 VCIs zertifizieren, BLE- und USB-Strecken erproben, `docs/geraetepruefung.md`
abarbeiten (jetzt inklusive Abschnitt 10, SFD). **Nicht** in dieser VM machbar;
was hier entsteht, ist die Anleitung und die Auswertung, nicht das Ergebnis.

### P9 — iOS *(nur mit macOS)*

`app-ios/` mit SwiftUI, Bindings über UniFFI, gleiche Flows. Ohne macOS-Runner
und Xcode nicht einmal übersetzbar. Der Plan nennt dafür S7 mit 140 PT — das ist
kein Nebenprodukt von P1–P7.

### P10 — Content auf 10 Modelle *(nur mit Fahrzeugen und Content-Team)*

S9 im Plan. Braucht belegte Funktionsdefinitionen je Modell, nicht Code.

## B.2 Reihenfolge

```
P1 ──┬─→ P2 ──┐
     └─→ P3 ──┴─→ P4 ──→ P5 ──→ P6 ──→ P7
                                      └─→ (P8, P9, P10 mit Ressourcen)
```

P1 zuerst und ohne Abkürzung: jede spätere Stufe ruft denselben `runde()`-Pfad.
Wer P1 überspringt und in `sfd.rs` weiterschreibt, hat am Ende zwei
NRC-Behandlungen, die sich unterschiedlich verhalten — und keinen Test, der das
merkt.

## B.3 Definition of Done (aus [implementierungsplan.md](implementierungsplan.md) §6)

1. Unit-Tests ohne Hardware laufen — **hier erfüllbar** (Loopback).
2. HIL-Test gegen ein echtes Steuergerät dokumentiert — **hier nicht erfüllbar**.
3. Fehlerpfade getestet: Timeout, NRC, Busabbruch, Stromausfall mitten im
   Schreiben — **teilweise hier** (alles außer echtem Stromausfall).
4. Fuzzing ≥ 10 Minuten ohne Absturz — **erledigt am 17.11.2026**: `sh
   scripts/fuzz.sh`, 604 s, kein Absturz ([ci.md](ci.md)). Die CI führt es
   weiterhin nicht aus.
5. Öffentliche API dokumentiert, keine `unwrap()` auf fremden Daten — **hier
   erfüllbar**.
6. Audit-Eintrag vorhanden und exportierbar — **hier erfüllbar** (P5).
7. Risikoklasse zugeordnet und in der Freigabematrix eingetragen — **hier
   erfüllbar** (Daten, P4).

Punkt 2 ist der Grund, warum M1 ohne Hardware **nicht** abgeschlossen werden
kann. Ein Agent, der das anders darstellt, hat den Meilenstein nicht verstanden.

## B.4 Ersatzmaßstab, solange die Hardware fehlt

Damit die Arbeit nicht stillsteht und trotzdem messbar bleibt:

- **Abdeckung der Dienste:** jeder Dienst aus B.1 mit positivem, NRC- und
  Längenfall — als Tabelle in der Doku geführt.
- **Korpus:** jede neue Parser-Fassung gegen den C-Korpus (ISOTP) und gegen
  aufgezeichnete, **echte** Antworten, sobald welche existieren. Erfundene
  Rahmen sind als solche zu kennzeichnen.
- **Simulator:** `Loopback` und `usb_sim` bleiben die einzige Grundlage; ihre
  Grenzen stehen in der Doku, nicht im Kleingedruckten.

## B.5 Erste fünf Aufgaben (heute anfangbar)

1. `core/src/uds.rs` anlegen: `runde()`, `session()`, `read_did()` — mit Tests
   für positiv, NRC, zu kurz, 0x78. *(erledigt)*
2. `sfd.rs` auf `uds::runde()` umstellen; die damals 48 Kern-Tests mussten
   unverändert grün bleiben (sie waren das Regressionsnetz für den Umbau).
   *(erledigt)*
3. `core/src/j1979.rs` mit der DTC-Dekodierung beginnen — reiner Parser, kein
   Bus, sofort testbar. *(Dekodierung erledigt am 17.11.2026)*
4. `content/schema/funktion.schema.json` mit den Pflichtfeldern aus P4
   schreiben und eine Beispielfunktion validieren. *(erledigt)*
5. ~~`docs/android-app-modul.md` auf 157 Tests ziehen (offener Punkt A.4.4)~~ —
   erledigt: 174 in neunzehn Dateien.

## B.6 Prüfbefehle (unverändert gültig)

```sh
export PATH=$HOME/.cargo/bin:$PATH

sh scripts/kern-tests.sh                                   # C + Korpus + Rust + FFI
JAVA_HOME=/tmp/jdk sh scripts/jvm-tests.sh                 # App ohne Gerät
export JAVA_HOME=/tmp/jdk
./android/gradlew -p android :app:assembleDebug --console=plain
sh scripts/zahlen-pruefen.sh                               # Zahlen gegen Jobnamen
```

Fallen, die schon Zeit gekostet haben:

- Ohne `JAVA_HOME` bricht Gradle mit „JAVA_HOME is not set“ ab.
- Die `.so` aus `jniLibs` ist gegen Bionic gelinkt und auf glibc **nicht**
  ladbar; die JVM-Tests brauchen den Host-Build
  (`cargo build --release --manifest-path core-ffi/Cargo.toml`).
- `core-ffi/bindings/` ist gitignored — nach Änderungen an der FFI neu erzeugen,
  sonst übersetzt Kotlin gegen alte Signaturen.

---

**Zum Schluss, in einem Satz:** der Kern trägt (ISO-TP, SFD, 165 + 200 + 4 + 15
Prüfungen), die App ist gebaut und übersetzt (174 Tests), aber **nichts davon ist
an einem Fahrzeug gelaufen** und die CI startet seit 147 Läufen keinen Runner.
Phase 1 beginnt deshalb nicht mit Hardware, sondern mit dem allgemeinen
UDS-Client — und jede Aussage über Verhalten am Fahrzeug bleibt offen, bis
`docs/geraetepruefung.md` abgehakt ist.
