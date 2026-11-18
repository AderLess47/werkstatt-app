# android — Kotlin bindet gegen den Kern

Hier liegt **noch keine App**. Das ist der Linktest: Kotlin kompiliert die
UniFFI-Bindings und ruft tatsächlich in die Rust-Bibliothek. Ohne ihn wäre
„die Bindings werden erzeugt" nur eine Behauptung.

```
settings.gradle.kts
build.gradle.kts           Kotlin 2.0.21
bindings/                  JVM-Modul, bindet ../../core-ffi/bindings/kotlin ein
  src/test/kotlin/IsoTpLinkTest.kt
```

Das Modul ist bewusst **JVM, nicht Android**: Die Bibliothek wird für den Host
gebaut, und ein JVM-Test kann sie ohne Emulator laden. Genau das prüft den
Aufruf durch die Sprachgrenze — dieselbe Grenze, die auf dem Gerät gilt.

## Was der Test beweist

1. Die erzeugten Kotlin-Bindings **kompilieren**.
2. JNA findet und lädt `libvci_ffi.so`.
3. Der **Callback in Gegenrichtung** funktioniert: Kotlin implementiert
   `CanTransport`, der Rust-Kern ruft hinein.
4. Die Logik läuft wirklich in Rust: Single Frame mit Padding, Multi-Frame in
   FF + CF mit fortlaufender Sequenz, Zeitüberschreitung als `VciException`.

## Fremdmodule

Welche Module aus Google, Maven Central und JitPack im aufgelösten Gradle-Graph
stecken (gemessen am 18.11.2026: 104) und unter welcher Lizenz, steht in
[`abhaengigkeiten.txt`](abhaengigkeiten.txt).
`scripts/abhaengigkeiten-pruefen.sh` hält den Graphen dagegen — auch transitive
Module; ein neues Modul bricht den Lauf, bis die Lizenz eingetragen ist.
Einzelheiten: [docs/ci.md](../docs/ci.md).

## Lokal ausführen

```bash
./scripts/gen-bindings.sh kotlin        # erzeugt Bindings + Host-Bibliothek
./android/gradlew -p android :bindings:test   # JDK 17+; Gradle-Version aus dem Wrapper
```

`VCI_LIB_DIR` überschreibt den Pfad zur Bibliothek (Standard:
`core-ffi/target/release`).

## Was hier nicht passiert

- **Kein Android-Build.** Kein AGP, kein Manifest, keine Activity — das ist
  Sprint S6 und braucht die App, die es noch nicht gibt. Ein leeres
  App-Gerüst wäre ein Platzhalter, der beim ersten echten Bauen umfällt.
- **Kein Emulator, kein Gerät.** Der Test läuft auf der JVM des Runners.
- **Die Android-`.so` entsteht in der CI** (`cargo ndk`, arm64-v8a und x86_64)
  und wird als Artefakt hochgeladen — hier ist kein NDK installiert.
