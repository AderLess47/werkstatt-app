# Android-App-Modul

Stand: erster Bildschirm der Werkstatt-App. Er beantwortet genau eine Frage —
**ist der Kern auf diesem Gerät arbeitsfähig?** — und geht dabei den ganzen Weg
vom Rust-Kern bis zum Pixel: `libvci_ffi.so` → JNA → UniFFI-Bindings → Kotlin →
Compose.

Kein Fahrzeug, kein Dongle, keine Diagnose. Der Transport auf diesem Bildschirm
ist ein Simulator im Arbeitsspeicher (`SimulatorTransport`), der auf
TesterPresent antwortet. Alles andere wäre ohne Hardware eine Behauptung.

## Aufbau

| Modul | Was es ist | Zweck |
| --- | --- | --- |
| `android/bindings` | JVM-Modul | kompiliert die erzeugten Bindings gegen den Host-Build des Kerns; die Linktests laufen hier |
| `android/app` | Android-App-Modul | Compose-Oberfläche, Selbsttest des Kerns, später VCI-Status |

Die Bindings werden nicht im Repository gehalten, sondern mit
`./scripts/gen-bindings.sh kotlin` erzeugt (siehe `docs/adr-001-kernsprache.md`).

## Bauen

```sh
# 1. Kern und Bindings
cargo build --release --manifest-path core-ffi/Cargo.toml
./scripts/gen-bindings.sh kotlin

# 2. SDK-Ziel (Pfad in android/local.properties)
echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# 3. APK
gradle -p android :app:assembleDebug
```

Ohne NDK enthält die APK **keine** `libvci_ffi.so`; `jniLibs` bleibt leer und der
Bildschirm meldet „Kern nicht ladbar“ — das ist die ehrliche Anzeige, nicht ein
Fehler der Oberfläche. Mit NDK (Getestet mit NDK 26.1.10909125):

```sh
# cargo-ndk liest Cargo.toml im Arbeitsverzeichnis; --manifest-path geht nicht.
cd core-ffi
cargo ndk --platform 29 -t arm64-v8a -t x86_64 -o ../dist/androidJniLibs build --release
cd ..
mkdir -p android/app/src/main/jniLibs
cp -r dist/androidJniLibs/* android/app/src/main/jniLibs/
gradle -p android :app:assembleDebug
```

`--platform 29` muss zu `minSdk` passen, sonst bindet der Linker gegen eine
ältere Bionic. Für die Cross-Kompilierung braucht die Toolchain die
`rust-std`-Bibliotheken für `aarch64-linux-android` und `x86_64-linux-android`
(`rustup target add …`).

`jniLibs` ist nicht versioniert; der CI-Job `android` füllt es und baut die APK.

Für die Werkstatt gibt es dazu eine eigene Anleitung mit allen Befunden und
Gegenmaßnahmen: [selbsttest-anleitung.md](selbsttest-anleitung.md).

## Transport

Der Transport ist **USB-Host** (die App ist Host, der VCI das Gerät) und
spricht exakt das Drahtformat, das der C-Kern unter Windows schon spricht
(`j2534/src/transport_usb.c`):

```
0xAA 0x55 | Typ (1) | Länge (1) | Nutzlast | CRC16 (2, LE)
```

| Datei | Aufgabe |
| --- | --- |
| `transport/Wire.kt` | Format: Rahmen bauen, CRC-16/CCITT-FALSE, CAN-Nutzlast, Spannung; `RahmenLeser` puffert, resynchronisiert, verwirft CRC-Fehler |
| `transport/UsbCanTransport.kt` | `CanTransport` über `UsbDeviceConnection.bulkTransfer`; sucht ein Gerät mit herstellerspezifischer Schnittstelle und Bulk-Endpunkten |
| `transport/UsbDongleVerbindung.kt` | Gerätesuche, USB-Freigabe per Broadcast, Öffnen, Prüflauf |
| `transport/BleCanTransport.kt` | `CanTransport` über eine GATT-Benachrichtigung; **kennt kein Android**, nimmt nur eine Schreibfunktion und `empfangen()` |
| `transport/BleGattVerbindung.kt` | Scan auf die Dienst-UUID, Verbindung, Dienst-Suche, Benachrichtigung einschalten, Schreibquittungen |
| `DonglePruefung.kt` | Bordspannung und TesterPresent (0x3E 0x00) durch den Kern — für beide Wege dieselbe Prüfung |

Bewusst **nicht** im Transport: CAN-Kennungen filtern. Das macht der Rust-Kern
(`isotp.rs`); zwei Filter an zwei Stellen wären die nächste Fehlerquelle.

### BLE-GATT

Der BLE-Weg ist derselbe Vorgang ohne Kabel: dieselben Rahmen, nur über eine
Charakteristik geschrieben statt über einen Bulk-Endpunkt. Standardprofil ist
der **Nordic UART Service** (NUS), weil er die verbreitetste transparente
serielle Strecke über BLE ist; andere Profile lassen sich über die UUIDs im
Konstruktor von `BleGattVerbindung` setzen. Der Scan filtert auf die Dienst-UUID,
zeigt also keine Kopfhörer und Sensoren aus der Werkstatt an.

Zwei Eigenheiten von BLE stecken im Transport:

1. **Benachrichtigungen kommen asynchron** auf einem eigenen Thread. `recv`
   wartet deshalb auf ein Signal (`wait`/`notifyAll`) statt zu pollen, und ein
   Verbindungsabbruch weckt es sofort — sonst hinge eine Diagnose bis zum Timeout.
2. **GATT erlaubt nur eine Schreiboperation zur Zeit.** `BleGattVerbindung`
   wartet auf die Quittung des Systems, bevor der nächste Rahmen geht, und
   schneidet längere Frames in 20-Byte-Stücke.

**Nicht unterstützt:** ELM327-Klone und andere ASCII-Dongles. Sie machen ISO-TP
selbst und liefern Textzeilen statt CAN-Rahmen — sie passen nicht durch
`CanTransport` und sind laut [Dongle-Matrix](vci-dongle-matrix.md) bewusst
ausgeschlossen. Der BLE-Weg setzt einen Dongle voraus, der unser Drahtformat
als Byte-Strecke spricht.

Die Bytes werden nicht nur behauptet, sondern gegen den C-Code geprüft: ein
C-Programm baut dieselben Rahmen und `WireTest` vergleicht hexadezimal, inklusive
des Prüfwerts `CRC-16/CCITT-FALSE("123456789") = 0x29B1`.

## Was der Bildschirm zeigt

- **Kern geladen** — die Bibliothek lädt, `IsoTp()` lässt sich erzeugen; die
  Adressierung (0x7E0 → 0x7E8) kommt aus dem Kern, nicht aus Kotlin.
- **ISO-TP** — eine Anfrage geht durch Segmentierung, Transport und
  Wiederzusammensetzung zurück. Geprüft wird die Nutzlast, nicht nur „kein
  Absturz“.
- Fehler werden als `FEHLER` mit der Meldung des Kerns angezeigt, inklusive
  fehlender Bibliothek (`UnsatisfiedLinkError`).

## Geprüft / nicht geprüft

Geprüft: `:bindings:test` (drei Linktests gegen die echte Rust-Bibliothek),
`:app:testDebugUnitTest` (22 Tests auf der JVM: Kern-Selbsttest, Drahtformat
gegen die C-Werte, Rahmenleser mit Teilstücken/Müll/CRC-Fehler, Dongle-Prüfung
gegen ein Gerät auf Drahtebene, BLE-Transport inklusive byteweiser
Benachrichtigungen, Aufwachen aus `recv` und ISO-TP über die Byte-Strecke),
`:app:assembleDebug`
(Kompilierung, Ressourcen, Manifest, Paketierung), APK-Inhalt
(startbare Activity `de.werkstatt.vci.MainActivity`, minSdk 29), die
NDK-Übersetzung beider ABIs (`readelf`: AArch64 bzw. X86-64, nur `libdl`/`libc`
als Abhängigkeit — Bionic, kein glibc), 29 exportierte `uniffi_vci_ffi_*`-Symbole
darunter `fn_constructor_isotp_new`, und dass `libvci_ffi.so` für `arm64-v8a`
und `x86_64` in der APK liegt.

Nicht geprüft: **Start auf einem Gerät oder Emulator**. In dieser VM ist
`/dev/kvm` für den Benutzer nicht zugänglich, ein Emulator wäre ohne
Hardwarebeschleunigung nicht lauffähig; echte Hardware ist nicht angebunden.
Damit ungeprüft bleiben: das `dlopen` aus `jniLibs` zur Laufzeit, die Anzeige
selbst, das Verhalten bei fehlender Bibliothek, **jede USB-Strecke**
(Gerätesuche, Freigabedialog, `bulkTransfer`) und **jede BLE-Strecke** (Scan,
Verbindungsaufbau, Rechte-Dialog, Benachrichtigungen, MTU-Verhalten, Abbruch bei
Reichweitenverlust). Ohne Dongle und Fahrzeug zeigt die Prüfung auf dem Gerät
ehrlich „kein USB-Dongle gefunden“ bzw. „kein Dongle gefunden“. Die JVM-Tests
zeigen, dass Format, Rahmenleser und Transportlogik stimmen — nicht, dass
Bluetooth oder USB auf einem echten Tablet funktionieren.

## Nächste Schritte

1. Gerätetest mit NDK-gebauter Bibliothek (arm64-v8a).
2. Transport an die Plattform hängen: BLE-GATT oder USB-Host → `CanTransport`.
3. Erst danach VCI-Status und der erste lesende Dienst am Fahrzeug.
