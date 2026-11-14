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

Fährt die Tests, die zu den **gepushten Dateien** passen, und bricht den Push ab,
wenn einer rot ist:

| geänderte Pfade | was läuft |
| --- | --- |
| `docs/`, `*.md`, `artifacts/`, `model/` | nichts — „nur Doku geändert“ |
| `core/`, `core-ffi/`, `j2534/`, `scripts/kern-tests.sh` | `scripts/kern-tests.sh` |
| `android/`, `scripts/jvm-tests.sh` | `scripts/jvm-tests.sh` |
| alles andere: Workflows, `.githooks/`, andere Skripte | beide |
| neuer Branch oder unbekannte Vergleichsbasis | beide |

`scripts/kern-tests.sh` prüft C-Kern (159 Prüfungen), Korpus-Aktualität,
Rust-Kern und FFI-Schicht; `scripts/jvm-tests.sh` die 119 JVM-Tests der App.

Die Einordnung ist bewusst grob: ein Pfad, den die Tabelle nicht kennt, lässt
beide Läufe starten. Im Zweifel wird geprüft, nicht geraten. Beide Läufe werden
auch dann gefahren, wenn der erste rot ist — wer ohnehin nicht pushen kann, soll
alle Befunde auf einmal sehen.

Voraussetzungen:

- `make` und ein C-Compiler für den C-Kern,
- `cargo` für Rust-Kern und FFI-Schicht,
- JDK 17 oder neuer und der Host-Build der Bibliothek
  (`cargo build --release --manifest-path core-ffi/Cargo.toml`) für die JVM-Tests,
- Compiler und Jars für die JVM-Tests: aus dem Gradle-Cache, sonst von Maven
  Central.

Fehlt eines davon, bricht der Hook mit einer Anleitung ab, statt den Teil still
zu überspringen.

Überspringen im Notfall:

```
VCI_SKIP_HOOK=1 git push
```

Der Hook schreibt dann hin, dass er übersprungen wurde. Absicht: ein stiller
Sprung wäre von „grün“ nicht zu unterscheiden. `git push --no-verify` umgeht den
Hook ganz — das ist Gits eigenes Verhalten und braucht hier keine Regel.

Der Hook ersetzt **nicht** die CI. Er prüft nur, was auf der Kommandozeile
prüfbar ist — kein Android-SDK, kein Gerät, keine APK. `:app:assembleDebug` und
der Start auf einem Tablet bleiben Sache des CI-Jobs `android` bzw. von
[docs/geraetepruefung.md](../docs/geraetepruefung.md).
