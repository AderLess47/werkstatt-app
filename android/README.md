# android — Kotlin bindet gegen den Kern

Hier liegt das Android-Projekt: der Linktest (`:bindings`) und die App (`:app`).

```
settings.gradle.kts
build.gradle.kts           Kotlin 2.0.21, AGP 8.5.2
bindings/                  JVM-Modul, bindet ../../core-ffi/bindings/kotlin ein
app/                       Compose-App: Selbsttest von Kern und Dongle
```

`bindings` ist bewusst **JVM, nicht Android**: Die Bibliothek wird für den Host
gebaut, und ein JVM-Test kann sie ohne Emulator laden. Genau das prüft den
Aufruf durch die Sprachgrenze — dieselbe Grenze, die auf dem Gerät gilt.

## Was der Linktest beweist

1. Die erzeugten Kotlin-Bindings **kompilieren**.
2. JNA findet und lädt `libvci_ffi.so`.
3. Der **Callback in Gegenrichtung** funktioniert: Kotlin implementiert
   `CanTransport`, der Rust-Kern ruft hinein.
4. Die Logik läuft wirklich in Rust: Single Frame mit Padding, Multi-Frame in
   FF + CF mit fortlaufender Sequenz, Zeitüberschreitung als `VciException`.

## Lokal ausführen

```bash
./scripts/gen-bindings.sh kotlin        # erzeugt Bindings + Host-Bibliothek
gradle -p android :bindings:test         # JDK 17+, Gradle 8.9
gradle -p android :app:testDebugUnitTest # Selbsttest-Logik auf der JVM
gradle -p android :app:assembleDebug     # APK
```

`VCI_LIB_DIR` überschreibt den Pfad zur Bibliothek (Standard:
`core-ffi/target/release`).

## Was hier nicht passiert

- **Kein Emulator, kein Gerät.** Die Tests laufen auf der JVM des Runners.
- **Die Android-`.so` entsteht in der CI** (`cargo ndk`, arm64-v8a und x86_64)
  und wird als Artefakt hochgeladen. Die App findet sie erst danach in
  `jniLibs`; ohne sie meldet der Bildschirm ehrlich „Kern nicht ladbar".
