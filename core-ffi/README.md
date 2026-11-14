# core-ffi — die Schnittstelle für Kotlin und Swift

Dünne Schicht über [`core/`](../core): hier liegt **keine Logik**, nur die
Übersetzung zwischen Rust-Typen und dem, was UniFFI nach Kotlin und Swift
ausgibt. Entscheidung und Auflagen: [ADR-001](../docs/adr-001-kernsprache.md).

**UniFFI-Version ist exakt gepinnt (`=0.32.2`).** UniFFI ist vor 1.0 und bricht
zwischen Minor-Versionen — Upgrades nur an Sprint-Grenzen, danach die Bindings
neu erzeugen und beide Plattformen bauen.

## Die Schnittstelle

| Rust | Kotlin | Swift |
|---|---|---|
| `IsoTp` (Object) | `IsoTp()` | `IsoTp` |
| `CanTransport` (Callback) | `interface CanTransport` | `protocol CanTransport` |
| `CanFrameFfi` (Record) | `data class CanFrameFfi` | `struct CanFrameFfi` |
| `VciError` (Error) | `sealed class VciException` | `enum VciError: Error` |
| `LoopbackTransport` (Object) | `LoopbackTransport()` | `LoopbackTransport` |

Die **IDL ist der annotierte Rust-Code** in [src/lib.rs](src/lib.rs)
(Proc-Macros, der seit 0.24 empfohlene Weg). Eine separate `.udl`-Datei gibt es
bewusst nicht: zwei Beschreibungen derselben Schnittstelle driften.

Entscheidend für die Plattformen: der **Transport kommt von außen**. Kotlin
implementiert `CanTransport` mit dem BLE- oder USB-Host-Stack, Swift mit
CoreBluetooth — der Kern kennt weder `BluetoothGatt` noch `CBPeripheral`.

## Kotlin (Android)

```kotlin
class BleTransport(private val gatt: BluetoothGatt) : CanTransport {
    override fun send(frame: CanFrameFfi) { /* Write ohne Response auf die TX-Charakteristik */ }

    override fun recv(timeoutMs: UInt): CanFrameFfi? =
        readFrameFromQueue(timeoutMs.toLong())   // null = Zeitüberschreitung

    override fun vbatMv(): UInt? = null          // misst das Gerät nicht
}

val iso = IsoTp()
try {
    iso.send(transport, byteArrayOf(0x3E, 0x00))
    val antwort: ByteArray = iso.recv(transport, 500u)
} catch (e: VciException.Timeout) {
    // nichts empfangen — Zündung? Kabel?
} catch (e: VciException.Protocol) {
    // e.detail nennt die Stelle im Ablauf (nicht `message`: das verdeckt
    // Throwable.message und die generierte Datei kompiliert dann nicht)
}
```

## Swift (iOS)

```swift
final class BleTransport: CanTransport {
    func send(frame: CanFrameFfi) throws { /* ... */ }
    func recv(timeoutMs: UInt32) throws -> CanFrameFfi? { nil }
    func vbatMv() throws -> UInt32? { nil }
}

let iso = IsoTp()
do {
    try iso.send(transport: transport, payload: Data([0x3E, 0x00]))
    let antwort = try iso.recv(transport: transport, timeoutMs: 500)
} catch VciError.Timeout {
    // ...
}
```

Die genauen Signaturen stehen in den erzeugten Dateien — sie sind die Wahrheit,
diese Beispiele sind Absicht, keine Zusage.

## Bauen

```bash
./scripts/gen-bindings.sh            # Kotlin + Swift für die Host-Bibliothek
./scripts/gen-bindings.sh kotlin     # nur eine Sprache
```

Ergebnis (nicht im Repository, siehe `.gitignore`):

```
core-ffi/bindings/kotlin/uniffi/vci_ffi/vci_ffi.kt
core-ffi/bindings/swift/vci_ffi.swift
core-ffi/bindings/swift/vci_ffiFFI.h          # C-Header für die Swift-Anbindung
core-ffi/bindings/swift/vci_ffiFFI.modulemap
```

Für echte Geräte muss die **Bibliothek** für die Ziel-ABIs gebaut werden, nicht
nur die Bindings:

```bash
# Android (NDK erforderlich)
cargo ndk -t arm64-v8a -t x86_64 -o app-android/app/src/main/jniLibs build --release

# iOS (nur auf macOS): statische Bibliotheken je Architektur, dann XCFramework
cargo build --release --target aarch64-apple-ios
cargo build --release --target aarch64-apple-ios-sim
xcodebuild -create-xcframework -library ... -output VciCore.xcframework
```

## In der CI

`.github/workflows/ci.yml` baut den Kern, erzeugt die Bindings als Artefakt und
baut im Android-Job die `.so` für arm64-v8a und x86_64, lässt Kotlin dagegen
linken und packt die APK. Ein iOS-Job ist dort **nicht** aktiv — er braucht einen
macOS-Runner, und ein Job, der nur so tut als ob, wäre schlimmer als keiner.

## Stand

**Verifiziert:** Crate baut mit `uniffi 0.32.2` ohne Warnungen, 2 Einheitstests
grün (Rundlauf über den Callback, Zeitüberschreitung als `VciError`), Bindings
für Kotlin und Swift werden erzeugt und enthalten die erwartete Fläche
(`IsoTp`, `CanTransport`, `CanFrameFfi`, `VciException`/`VciError`). Das Kotlin
kompiliert und ruft in die Bibliothek (`:bindings:test`, 3 Tests grün).

**Nicht verifiziert:** Swift wurde **nicht kompiliert** — dafür braucht es Xcode.
Dass die Bindings syntaktisch korrekt sind, ist belegt (UniFFI erzeugt sie aus
Metadaten), nicht dass sie in einem Projekt bauen.
