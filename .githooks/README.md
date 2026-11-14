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

Lässt `scripts/jvm-tests.sh` laufen und bricht den Push ab, wenn die 119 Tests
nicht grün sind. Rot heißt: nichts wird übertragen, also auch kein Commit, der
den Build bricht.

Voraussetzungen (wie beim Skript selbst):

- JDK 17 oder neuer,
- der Host-Build der Bibliothek:
  `cargo build --release --manifest-path core-ffi/Cargo.toml`,
- Compiler und Jars: aus dem Gradle-Cache, sonst von Maven Central.

Überspringen im Notfall:

```
VCI_SKIP_HOOK=1 git push
```

Der Hook schreibt dann hin, dass er übersprungen wurde. Absicht: ein stiller
Sprung wäre von „grün“ nicht zu unterscheiden.

Der Hook ersetzt **nicht** die CI. Er prüft nur, was die JVM prüfen kann — kein
Android-SDK, kein Gerät, keine APK. `:app:assembleDebug` und der Start auf einem
Tablet bleiben Sache des CI-Jobs `android` bzw. von
[docs/geraetepruefung.md](../docs/geraetepruefung.md).
