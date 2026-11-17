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
| `docs/`, `*.md`, `artifacts/`, `model/` | alle fünf Jobs |
| `core/`, `core-ffi/`, `j2534/`, `scripts/kern-tests.sh`, `scripts/ci-lokal.sh` | Jobs `c`, `rust`, `clippy`, `fmt` |
| `android/`, `scripts/jvm-tests.sh` | Job `jvm-tests` |
| `core/src/isotp.rs`, `core/src/uds.rs`, `core/fuzz/` | Jobs `c`, `rust`, `clippy`, `fmt` und `scripts/fuzz.sh` (Rauchlauf) |
| alles andere: Workflows, `.githooks/`, andere Skripte | alle fünf Jobs |
| neuer Branch oder unbekannte Vergleichsbasis | alle fünf Jobs |

Die Jobs sind dieselben wie in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml):
der Hook prüft C-Kern (165 Prüfungen) samt Korpus-Aktualität, 184 Tests im
Rust-Kern, 4 Korpus-Tests und 15 in der FFI-Schicht, dazu `clippy`
(`--all-targets`, `-D warnings`), `fmt` (`cargo fmt --check`)
und die 174 JVM-Tests der App. Gefahren werden sie von `scripts/ci-lokal.sh`,
das auch einzeln aufrufbar ist:

```
sh scripts/ci-lokal.sh        # alle fünf
sh scripts/ci-lokal.sh fmt    # nur die Formatprüfung
```

Der Hook ruft dieses Skript statt der einzelnen Skripte, weil der Workflow in
diesem Repository nicht startet (kein Runner, [docs/ci.md](../docs/ci.md)) — was
der Hook prüft, soll dasselbe sein, was in der CI angekündigt ist.

Reine Doku fährt seit dem 17.11.2026 ebenfalls alle fünf Jobs. Vorher
übersprang sie der Hook mit „nur Doku geändert“; damit war ein Doku-Push der
einzige Fall, in dem der Hook gar nichts über den Push sagte. Ein warm
gerechneter Lauf dauert rund 40 s — das ist zu wenig, um dafür eine zweite
Regel im Kopf behalten zu müssen. Die Auswahl nach Pfaden beschränkt sich
jetzt auf das, was wirklich teuer oder unmöglich wäre: eine Änderung an
`j2534/` startet keine JVM-Tests, eine an `android/` keine Cargo-Prüfungen.

Der Fuzz-Rauchlauf ist der kurze Bruder des Zehn-Minuten-Laufs aus der Definition
of Done: 20 Sekunden je Ziel, und nur, wenn Parser-Code angefasst wurde. Er
braucht `nightly` und `cargo-fuzz`, die nicht zur normalen Werkzeugkette gehören;
fehlen sie, sagt der Hook das und lässt den Schritt aus — sichtbar, nicht still.
Die Länge stellt `VCI_FUZZ_SEKUNDEN` ein.

## Formatprüfung

Der Job `fmt` prüft das **ganze** Crate: `cargo fmt --check` für `core`,
`core-ffi` und `core/fuzz` — derselbe Befehl wie im Workflow. Bis zum 17.11.2026
ging das nicht: das Crate war von Hand formatiert, und ein Lauf über alles hätte
jeden Push blockiert, bis der Reformat übertragen war (acht Dateien, rund
184 KB, siehe [docs/ci.md](../docs/ci.md)). Seit er übertragen ist, gilt die
strenge Regel: was gepusht wird, ist überall rustfmt-sauber.

Beheben:

```
cargo fmt --manifest-path core/Cargo.toml
```

`cargo fmt` braucht die rustfmt-Komponente. Fehlt sie, lässt der Hook den Job aus
und sagt es — sichtbar, wie beim Fuzz-Rauchlauf; ein Werkzeugausfall ist kein
Formatbefund. Geprüft wird mit `cargo fmt --version`, nicht mit `command -v`:
rustup legt seine Proxy-Binaries unabhängig von den installierten Komponenten in
`~/.cargo/bin` ab, ein Proxy allein sagt also nichts. Fehlt das Werkzeug, nennt
auch der Trockenlauf den Job als ausgelassen statt ihn anzukündigen.

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
Formatprüfung beim ersten Push ausgelassen. Dasselbe Skript holt auch
Android SDK, NDK und Gradle 8.9 und baut die Bibliotheken für `jniLibs` — also
alles, was den APK-Build braucht; `--probe` zeigt nur, was fehlen würde,
`OHNE_ANDROID=1` lässt den Android-Teil aus.

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

Auch `scripts/ci-lokal.sh` ist kein Runner: es prüft den Arbeitsbaum statt eines
frischen Klons, mit den Werkzeugen dieser Maschine. Was der Lauf belegt und was
nicht, steht in [docs/ci.md](../docs/ci.md) unter „Lokaler Ersatz“.
