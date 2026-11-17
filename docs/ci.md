# CI — was läuft, und wo sie gerade steht

`.github/workflows/ci.yml` läuft bei jedem Push und jedem Pull Request.

| Job | Was er prüft |
| --- | --- |
| `c` | `make -C j2534 test` (165 Prüfungen) und dass `core/tests/corpus/isotp.txt` unverändert aus dem Erzeuger hervorgeht |
| `rust` | `cargo test` für `core` (200 Tests, dazu 4 Korpus-Tests aus `core/tests/corpus/isotp.txt`) und für `core-ffi` (15 Tests) |
| `clippy` | `cargo clippy --all-targets -- -D warnings` für `core` und `core-ffi`; `--all-targets` schließt die Tests ein, und genau dort lagen die zwei Warnungen, die den Job sonst nie erreicht hätten |
| `fmt` | `cargo fmt --check` für `core`, `core-ffi` und `core/fuzz` — eigener Job, weil ein Formatfehler nichts über das Verhalten sagt |
| `bindings` | erzeugt Kotlin- und Swift-Bindings neu, lädt sie als Artefakt hoch (`needs: rust`) |
| `jvm-tests` | die 174 Tests der App ohne Gerät über `sh scripts/jvm-tests.sh`, gegen den Host-Build der Bibliothek |
| `android` | NDK-Bibliothek für `arm64-v8a` und `x86_64`; `./android/gradlew -p android :bindings:test`; dann `:app:testDebugUnitTest :app:assembleDebug` und eine Prüfung, dass beide ABIs in der APK liegen |

Der Android-Job baut über den Wrapper, nicht über ein vom Runner gestelltes
Gradle: die Version steht allein in
`android/gradle/wrapper/gradle-wrapper.properties`. `setup-gradle` bekommt
bewusst kein `gradle-version` — ohne diese Angabe nimmt die Aktion den Wrapper
des Projekts an und richtet nur den Cache ein. Damit gibt es genau einen Pin und
keine zweite Gradle-Distribution, die niemand benutzt.

Die Zahlen in den Jobnamen sind eine Ankündigung, keine Messung. Damit sie nicht
abdriften, zählt `sh scripts/zahlen-pruefen.sh` alle fünf lokal nach —
`make -C j2534 test`, beide `cargo test`, `scripts/jvm-tests.sh` — und bricht ab,
sobald eine Zahl von der im Workflow genannten abweicht (Exit 1). Es zählt, was
die Suiten melden, nicht was im Quelltext deklariert ist. Laufen kann es nur
lokal: die Jobs selbst werden ja nicht gestartet (siehe unten).

## Formatierung

`cargo fmt --check` läuft als eigener Job `fmt` für die drei Crates (`core`,
`core-ffi`, `core/fuzz`). Eigener Job und nicht ein Schritt in `rust`: ein
Formatfehler sagt nichts über das Verhalten, und der Lauf soll zeigen, ob nur
die Form abweicht — ohne die Tests dafür erneut zu fahren.

Der Weg dahin war der schwierige Teil. Das Crate ist über die Zeit von Hand
formatiert worden; ein Lauf mit rustfmt änderte am 17.11.2026 **zwölf Dateien
mit rund 235 KB** Quelltext.

Ein eigener Versuch, die Handformatierung per `rustfmt.toml` als Soll zu erklären
(`max_width = 110`, `use_small_heuristics = "Max"`, `reorder_imports = false`),
ist am 17.11.2026 gemessen gescheitert: rustfmt löst `=> { Ausdruck }` in einem
Match-Arm immer zu `=> Ausdruck,` auf, und dafür gibt es keine stabile Option
(`match_arm_blocks = false` ändert daran nichts). Genau diese Form steht in
`sfd.rs` und `isotp.rs`; außerdem sind Signaturen im Repo 101 Zeichen lang und
liegen damit schon über dem Vorgabewert 100. Eine Konfiguration, die den alten
Stand durchwinkt, gibt es also nicht — es bleibt beim Reformat.

Die Änderung selbst ist rein mechanisch gefahren — der Kern danach weiter grün
(damals 184 Tests, Clippy sauber). Der Haken war der Weg ins Repository: es gibt von
dieser Maschine keinen `git push`, jede Datei geht einzeln durch die API, und
`sfd.rs` allein hat 39 KB. Ein einzelnes verrutschtes Zeichen wäre ein
Programmierfehler in getestetem Code — deshalb stand der Job erst im Workflow,
als alle Dateien übertragen und jede gegen `git hash-object` geprüft war. Ein
Job, der beim ersten Lauf rot ist, prüft nichts.

Bei `core/src/uds.rs` hat diese Prüfung gegriffen: der Blob im Repository
(`20309ff3`) wich vom lokal formatierten (`856f4d5a`) ab, weil die
typografischen Schlusszeichen („…“) als gerade Anführungszeichen übertragen
worden waren. Commit `7a1c601` korrigiert das; erst danach stimmte der Blob.

**Stand am 17.11.2026.** Alle acht vorher offenen Dateien sind formatiert und im
Repository, jede per Blob-Hash bestätigt:

| Datei | Größe |
| --- | --- |
| `core/src/audit.rs` | 20 KB |
| `core/src/content.rs` | 28 KB |
| `core/src/function.rs` | 32 KB |
| `core/src/function/tests.rs` | 16 KB |
| `core/src/isotp.rs` | 17 KB |
| `core/src/lib.rs` | 1,5 KB |
| `core/src/sfd.rs` | 39 KB |
| `core/src/uds.rs` | 56 KB |

`cargo fmt --check` ist danach für alle drei Crates lokal grün (Exit 0), und
`sh scripts/kern-tests.sh` läuft durch (200 Rust-Tests).

## Fuzzing — lokal belegt, nicht in der CI

Punkt 4 der Definition of Done ([implementierungsplan.md](implementierungsplan.md)
§6) verlangt Fuzzing des Parser-Codes. Ein Job dafür steht bewusst nicht im
Workflow: er bräuchte nightly und `cargo-fuzz` auf dem Runner, und die Jobs
starten ohnehin nicht (siehe unten). Gefahren wird lokal, mit fester Dauer:

```
sh scripts/fuzz.sh        # 300 s je Ziel
sh scripts/fuzz.sh 30     # Rauchprobe nach einer Änderung
```

Zwei Ziele in `core/fuzz/fuzz_targets/`: `isotp` (Empfangsweg über `Loopback`,
Sendeweg gegen ein Gerät mit Blockgröße 0–8, `dlc` absichtlich auch falsch) und
`uds` (die Parser für Sitzung, Kennung und Fehlerspeicher sowie die
NRC-Erkennung).

| Ziel | Dauer | Läufe | Korpus | Absturz |
| --- | --- | --- | --- | --- |
| `isotp` | 302 s | 33 494 974 | 66 Einheiten | keiner |
| `uds` | 302 s | 55 643 504 | 18 Einheiten | keiner |

Gemessen am 17.11.2026 in dieser VM (nightly 1.102, cargo-fuzz 0.13.2); zusammen
604 s, also die geforderten zehn Minuten. Gefahren wurde in Abschnitten, weil
ein einzelner Befehl hier nach rund 295 s abgebrochen wird — die Dauer ist die
Summe der Abschnitte, nicht ein Lauf am Stück.

**Was der Lauf nicht sagt:** kein Absturz heißt nicht keine Fehler. Belegt ist
die Abwesenheit eines Absturzes auf den erreichten Pfaden, nicht die
Korrektheit der Auswertung. Der Korpus liegt nicht im Repository
(`core/fuzz/corpus/` ist ignoriert) — jeder Lauf ohne ihn beginnt bei null.

## Stand: die Jobs werden nicht gestartet

Gemessen am 15.11.2026 über die GitHub-API:

| Frage | Antwort |
| --- | --- |
| Läufe insgesamt | 147 (gemessen 15.11.2026, 16:56 UTC; jeder Push zählt weiter) |
| davon erfolgreich | **0** |
| die fünf Läufe zu den SFD- und Doku-Commits (`11948610`…`e0ad5607`) | je `conclusion: failure`, Laufzeit 3–5 s |
| die acht Läufe zu den Commits dieser Sitzung (`f5ea5eb`…`41a81222`, Lauf 129–136) | je `conclusion: failure`, Laufzeit 4–5 s |
| letzter Lauf (`43737325391`, Commit `e0ad5607`, Lauf 147) | 5 Jobs: vier mit `conclusion: failure`, je `runner_id: 0`, `steps: []`; gestartet 16:46:11, beendet 16:46:14/15; `bindings` `skipped`, weil `rust` nicht gelaufen ist — dasselbe Bild wie beim vorigen Stand |
| Repository | **privat** (`private: true`) |
| Actions im Repository | `enabled: true`, `allowed_actions: all` |
| Actions-Minuten im Monat (Konto) | 978 Linux + 388 macOS, brutto rund 30 $, im Nutzungsbericht mit 0 $ netto ausgewiesen |
| Lauf des Commits, der diese Seite hinzufügte (#126) | `created_at` 13:44:10, `updated_at` 13:44:14, `conclusion: failure` |
| erneuter Lauf, Versuch 2 (#126, „Re-run failed jobs“) | 13:49:00 → 13:49:04, wieder `steps: []`, `runner_id: 0`, `failure` |

Die Zahl der Läufe wächst mit jedem Push; erfolgreich war bisher keiner — auch
der Lauf, der diese Seite ins Repository brachte, endete nach vier Sekunden
ohne Runner. Ein erneuter Lauf (Versuch 2) ändert daran nichts: gleiche Dauer,
gleiches Bild, kein einziger Schritt. Solange das so aussieht, ist die Sperre
nicht gefallen — unabhängig davon, was in den Abrechnungseinstellungen geändert
wurde. Nach einer Korrektur kann es dauern, bis sie greift; der nächste Beweis
ist ein Lauf mit `runner_id ≠ 0`.

Ein Job **ohne Schritte und ohne Runner** ist nicht an einer Prüfung gescheitert
— er wurde nicht gestartet. Die Meldung, die GitHub dazu anzeigt, lautet:

> The job was not started because recent account payments have failed or your
> spending limit needs to be increased. Please check the 'Billing & plans'
> section in your settings.

Das Bild ist in sich stimmig: die Rechte im Repository sind offen, die
Workflow-Datei ist gültig (lokal mit einem YAML-Parser geladen, sieben Jobs,
`android` mit 13 Schritten), und es wurden in diesem Monat Minuten verbraucht.
Es fehlt also nicht an der Konfiguration, sondern an der Abrechnung.

## Was die Sperre löst — und wer es kann

Das kann nur der Kontoinhaber (oder ein Billing-Manager einer Organisation); von
dieser Maschine aus ist es nicht erreichbar. Drei Wege:

1. **Zahlungsmittel reparieren oder Budget anheben.** `github.com/settings/billing`
   → *Payment information* (Karte erneuern, abgelehnte Zahlung wiederholen) und
   *Budgets and alerts*: ein Budget mit „Stop usage when budget limit is reached“
   blockiert, sobald es aufgebraucht ist — auch ein 0-$-Budget. Budgets gibt es
   pro Produkt, pro Runner-Typ und pro Repository; eines davon genügt, um zu
   blockieren. Nach einer Zahlung kann es einige Zeit dauern, bis die Sperre
   fällt.
2. **Repository öffentlich machen.** Standard-Runner in öffentlichen
   Repositories sind kostenlos, die Abrechnung dieses Pfads greift dort nicht.
   Das ist eine Entscheidung über den Code, nicht über die Technik — das
   Repository ist derzeit privat.
3. **Selbst gehosteter Runner.** `runs-on` auf einen eigenen Runner zeigen; der
   zählt nicht zur Abrechnung. Braucht eine dauerhaft erreichbare Maschine, die
   es hier nicht gibt.

Was **nicht** hilft: die Workflow-Datei ändern, Rechte im Repository anpassen,
Jobs erneut starten („Re-run jobs“ scheitert genauso) oder die Trigger einschränken.

## Woran man erkennt, dass es wieder läuft

Ein Lauf, der die Sperre überwunden hat, bekommt einen Runner und Schritte.
Prüfbar über `GET /repos/AderLess47/werkstatt-app/actions/runs/{id}/jobs`:

- `runner_id` ist nicht 0,
- `steps` ist gefüllt (mindestens `Set up job`),
- die Laufzeit liegt über ein paar Sekunden.

Danach ist der erste grüne Lauf die eigentliche Prüfung — bis dahin ist in
diesem Repository **kein** CI-Ergebnis belegt.

## Lokaler Ersatz: `scripts/ci-lokal.sh`

Weil die Jobs nicht starten, fährt ein Skript dieselben fünf Jobs mit denselben
Befehlen auf dieser Maschine:

```
sh scripts/ci-lokal.sh
```

Gemessen am 17.11.2026, 15:18 UTC in dieser VM (rustc 1.100.0, cargo 1.100.0,
OpenJDK 17.0.21, Linux 6.1.102 x86_64) — alle fünf Jobs grün in zusammen 47 s.
Der Lauf davor am selben Tag (14:42 UTC) hatte denselben Umfang, aber 184 Tests
im Kern; die 16 seitdem gekommenen Tests sind `0x31` (RoutineControl) in
`core/src/uds.rs` — Start, Stop, Ergebnis und der Statusrecord —, samt der
SFD-Nachrichten, die jetzt über denselben Baukasten laufen. Der `rust`-Job
brauchte diesmal 5 s statt 1 s, weil die Test-Binärdatei neu übersetzt wurde;
die 47 s sind deshalb nur bedingt mit den 38 s davor vergleichbar.

| Job | Ergebnis | Dauer | Was im Log steht |
| --- | --- | --- | --- |
| `c` | grün | 0 s | 165 `PASS`-Zeilen, Korpus byteweise unverändert |
| `rust` | grün | 5 s | 200 Tests im Kern, 4 Korpus-Tests, 15 in der FFI-Schicht |
| `clippy` | grün | 2 s | keine Warnung in `core` und `core-ffi` |
| `fmt` | grün | 0 s | `--check` für `core`, `core-ffi`, `core/fuzz` |
| `jvm-tests` | grün | 40 s | `OK (174 tests)` |

Die Dauern sind **mit warmem Build-Verzeichnis** gemessen — `target/` war vom
vorigen Lauf gefüllt. Ein kalter Lauf braucht Minuten; die Zahl sagt deshalb
nichts über die Laufzeit auf einem Runner, nur dass dieselben Prüfungen hier
durchlaufen.

Was der Lauf **nicht** belegt, damit die Aussage klein bleibt: geprüft wird der
Arbeitsbaum, kein frischer Klon; es laufen die Werkzeuge dieser Maschine, nicht
die der Runner-Images; ohne Artefakt-Upload und ohne Cache. `android` (SDK,
NDK) und `bindings` (Artefakt-Upload, Swift-Bindings brauchen macOS) fehlen
ganz und stehen in der Übersicht nicht als grün. Fehlt das JDK, steht
`jvm-tests` als „nicht gelaufen“ da und das Skript endet mit 1 — ein nicht
gefahrener Job ist kein bestandener.

Die Ausgaben liegen je Job in `dist/ci-lokal/<Zeitstempel>/` (ignoriert), die
Übersicht daneben in `uebersicht.txt`. Der Korpus wird dort byteweise gegen die
neu erzeugte Fassung verglichen statt über `git diff`: der Arbeitsbaum kann vom
Index abweichen, und ein Vergleich gegen den Index meldete dann eine Änderung,
die es nicht gibt.

## Erster Lauf nach der Sperre

### Was das für den Stand der Verifikation heißt

Solange 0 von 147 Läufen erfolgreich sind, stützt sich keine Aussage in der
Dokumentation auf die CI. Belegt ist, was lokal gelaufen ist: `make -C j2534
test`, `cargo test` für beide Crates, `sh scripts/jvm-tests.sh` (174 Tests), der
Fuzz-Lauf über `sh scripts/fuzz.sh` (604 s, siehe oben) und der Android-Build
über den Wrapper
(`./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug`).
Ungeprüft bleibt alles, was nur auf dem Runner passiert: `cargo ndk` für beide
ABIs, das Verhalten von `setup-gradle` ohne `gradle-version`, die
Artefakt-Uploads und die Abhängigkeitsauflösung über JitPack auf einem frischen
Rechner.
