# android — App und Kotlin-Linktest

Die App (Compose-Oberfläche, USB- und BLE-Transport) und daneben das JVM-Modul,
das die UniFFI-Bindings gegen die Rust-Bibliothek prüft. Beides gehört zur
selben Gradle-Wurzel.

```
settings.gradle.kts
build.gradle.kts                 Kotlin 2.0.21
bindings/                        JVM-Modul, bindet ../core-ffi/bindings/kotlin ein
  src/test/kotlin/IsoTpLinkTest.kt
app/                             Android-App (minSdk 29, targetSdk 35)
  src/main/kotlin/de/werkstatt/vci/
    MainActivity.kt              Prüfbildschirm
    VciCheck.kt                  Kern-Prüfung ohne Dongle
    DonglePruefung.kt            Prüfung über den angeschlossenen Dongle
    transport/Wire.kt            Drahtformat, Rahmenleser
    transport/UsbCanTransport.kt USB-Host
    transport/BleCanTransport.kt BLE-GATT über NUS
  src/test/kotlin/               Tests gegen die echte .so und gegen Goldwerte
```

## Das JVM-Modul (`bindings/`)

Bewusst **JVM, nicht Android**: Die Host-Bibliothek lässt sich ohne Emulator
laden. Der Test prüft dieselbe Sprachgrenze, die auch auf dem Gerät gilt.

1. Die erzeugten Kotlin-Bindings **kompilieren**.
2. JNA findet und lädt `libvci_ffi.so`.
3. Der **Callback in Gegenrichtung** funktioniert: Kotlin implementiert
   `CanTransport`, der Rust-Kern ruft hinein.
4. Die Logik läuft wirklich in Rust: Single Frame mit Padding, Multi-Frame in
   FF + CF mit fortlaufender Sequenz, Zeitüberschreitung als `VciException`.

## Die App (`app/`)

Der Bildschirm zeigt zwei Dinge getrennt: die **Kern-Prüfung** (Adressierung,
ISO-TP-Zusammensetzung — läuft ohne Hardware) und die **Dongle-Prüfung**
(Spannung, Antwort des Steuergeräts über den angeschlossenen Transport).

Die App-Tests laufen auf der JVM gegen die für den Host gebaute Bibliothek
(`jna.library.path` zeigt auf `core-ffi/target/release`) und gegen Goldwerte
aus dem C-Code — sie ersetzen keinen Gerätetest, prüfen aber die Logik des
Bildschirms und das Drahtformat.

## Lokal ausführen

```bash
./scripts/gen-bindings.sh kotlin        # erzeugt Bindings + Host-Bibliothek
gradle -p android :bindings:test :app:testDebugUnitTest :app:assembleDebug
```

`VCI_LIB_DIR` überschreibt den Pfad zur Bibliothek (Standard:
`core-ffi/target/release`). Das NDK wird nur für die `.so` gebraucht, die auf
dem Gerät läuft; die CI baut sie mit `cargo ndk` (arm64-v8a, x86_64) und legt
sie unter `jniLibs` ab.

## Was hier nicht verifiziert ist

- **Kein Gerät, kein Emulator.** USB-Host und BLE sind gegen Attrappen und
  Goldwerte geprüft, nicht gegen echte Hardware. Der erste Lauf am Fahrzeug
  steht aus.
- **Keine Dongle-Matrix.** Welche BLE-Dongles das Drahtformat sprechen, ist in
  [docs/vci-dongle-matrix.md](../docs/vci-dongle-matrix.md) festgehalten.
