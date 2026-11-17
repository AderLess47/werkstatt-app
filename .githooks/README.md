# Hooks

Hier liegen die Git-Hooks, die für dieses Repository gelten. Sie sind nicht
automatisch aktiv — Git schaut von sich aus nur in `.git/hooks`, und das ist
nicht versioniert.

```
sh scripts/install-hooks.sh
```

Das setzt `core.hooksPath` auf `.githooks` und macht die Hooks ausführbar. Der
Weg über `core.hooksPath` ist Absicht: die Datei liegt im Repository, wandert mit
und kann nicht veralten — eine Kopie in `.git/hooks` tut beides nicht.

## `pre-push`

Fährt die Prüfungen, die zu den **gepushten Dateien** passen, und bricht den Push
ab, wenn eine rot ist:

| geänderte Pfade | was läuft |
| --- | --- |
| `docs/`, `*.md`, `artifacts/`, `model/` | nichts — „nur Doku geändert“ |
| `core/`, `core-ffi/`, `j2534/`, `scripts/kern-tests.sh` | `scripts/kern-tests.sh` |
| `android/`, `scripts/jvm-tests.sh` | `scripts/jvm-tests.sh` |
| `core/src/isotp.rs`, `core/src/uds.rs`, `core/fuzz/` | `scripts/kern-tests.sh` und `scripts/fuzz.sh` (Rauchlauf) |
| jede `*.rs` in der Dateiliste | zusätzlich `rustfmt --check` für genau diese Dateien |
| alles andere: Workflows, `.githooks/`, andere Skripte | alle |
| neuer Branch oder unbekannte Vergleichsbasis | alle |

`scripts/kern-tests.sh` prüft C-Kern (165 Prüfungen), Korpus-Aktualität,
Rust-Kern und FFI-Schicht; `scripts/jvm-tests.sh` die 174 JVM-Tests der App.

Der Fuzz-Rauchlauf ist der kurze Bruder des Zehn-Minuten-Laufs aus der Definition
of Done: 20 Sekunden je Ziel, und nur, wenn Parser-Code angefasst wurde. Er
braucht `nightly` und `cargo-fuzz`, die nicht zur normalen Werkzeugkette gehören;
fehlen sie, sagt der Hook das und lässt den Schritt aus — sichtbar, nicht still.
Die Länge stellt `VCI_FUZZ_SEKUNDEN` ein.

## Formatprüfung

`rustfmt --check` läuft für die **Rust-Dateien, die der Push anfasst** — nicht für
das ganze Crate. Grund: das Crate ist überwiegend von Hand formatiert, und ein
Lauf über alles würde jeden Push blockieren, bis der Reformat übertragen ist
(acht Dateien, rund 184 KB, siehe [docs/ci.md](../docs/ci.md)). Durchsetzbar ist
dagegen die Regel: wer eine Datei ändert, liefert sie formatiert ab.

Geprüft wird jede Datei einzeln mit `rustfmt --check --edition 2021 <datei>`.
Ist eine davon nicht sauber, nennt der Hook sie und bricht den Push ab. Beheben:

```
rustfmt --edition 2021 core/src/isotp.rs
```

`rustfmt` gehört zu den Rust-Komponenten. Fehlt es, sagt der Hook das und lässt
den Schritt aus — sichtbar, wie beim Fuzz-Rauchlauf. Bei unbekannter
Vergleichsbasis (neuer Branch, Force-Push) gibt es keine Dateiliste; dann wird
auch die Formatprüfung ausgelassen, und der Hook schreibt es hin.

Die Einordnung ist bewusst grob: ein Pfad, den die Tabelle nicht kennt, lässt
alle Läufe starten. Im Zweifel wird geprüft, nicht geraten. Alle Läufe werden
auch dann gefahren, wenn der erste rot ist — wer ohnehin nicht pushen kann, soll
alle Befunde auf einmal sehen.

## Trockenlauf

Zeigen, was ein Push jetzt prüfen würde — ohne zu pushen und ohne zu testen:

```
sh .githooks/pre-push --probe              # gegen den Upstream-Branch
sh .githooks/pre-push --probe origin/main  # gegen eine bestimmte Basis
```

Die Ausgabe nennt Basis, geänderte Dateien und die Auswahl. Es ist derselbe Code
wie im Push; unterschieden wird nur die Herkunft der Dateiliste (Diff gegen die
Basis statt Gits stdin), damit der Trockenlauf nicht anders entscheiden kann als
der Ernstfall. Ohne Upstream und ohne `origin/main` meldet er, dass alle
Prüfungen laufen würden.

## Voraussetzungen

- `make` und ein C-Compiler für den C-Kern,
- `cargo` für Rust-Kern und FFI-Schicht,
- `rustfmt` für die Formatprüfung (`rustup component add rustfmt`; fehlt es,
  wird der Schritt benannt und ausgelassen),
- JDK 17 oder neuer und der Host-Build der Bibliothek
  (`cargo build --release --manifest-path core-ffi/Cargo.toml`) für die JVM-Tests,
- Compiler und Jars für die JVM-Tests: aus dem Gradle-Cache, sonst von Maven
  Central.

Nach einem Sandbox-Restore (JDK und `target/` weg) stellt
`sh scripts/vorbereiten.sh` beides wieder her: Temurin 17 von Adoptium, danach
die Host-Bibliothek per `cargo build --release`. Fehlt `rustfmt`, holt dasselbe
Skript die Komponente per `rustup component add rustfmt` — sonst würde die
Formatprüfung beim ersten Push ausgelassen. Dasselbe Skript holt auch Android SDK,
NDK und Gradle 8.9 und baut die Bibliotheken für `jniLibs` — also alles, was den
APK-Build braucht; `--probe` zeigt nur, was fehlen würde, `OHNE_ANDROID=1` lässt
den Android-Teil aus.

Fehlt eines davon, bricht der Hook mit einer Anleitung ab, statt den Teil still
zu überspringen.

## Überspringen

```
VCI_SKIP_HOOK=1 git push
```

Der Hook schreibt dann hin, dass er übersprungen wurde. Absicht: ein stiller
Sprung wäre von „grün“ nicht zu unterscheiden. `git push --no-verify` umgeht den
Hook ganz — das ist Gits eigenes Verhalten und braucht hier keine Regel.

## Grenzen des Hooks

Der Hook ersetzt **nicht** die CI. Er prüft nur, was auf der Kommandozeile
prüfbar ist — kein Android-SDK, kein Gerät, keine APK. `:app:assembleDebug` und
der Start auf einem Tablet bleiben Sache des CI-Jobs `android` bzw. von
[docs/geraetepruefung.md](../docs/geraetepruefung.md).
