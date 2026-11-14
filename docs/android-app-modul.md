# Android-App-Modul

Stand: zwei Bausteine in einem Modul — der **Selbsttest-Bildschirm** des Kerns und
die **Transportschicht** zu echten Dongles.

Der Bildschirm beantwortet genau eine Frage — **ist der Kern auf diesem Gerät
arbeitsfähig?** — und geht dabei den ganzen Weg vom Rust-Kern bis zum Pixel:
`libvci_ffi.so` → JNA → UniFFI-Bindings → Kotlin → Compose. Auf diesem
Bildschirm ist der Transport ein Simulator im Arbeitsspeicher
(`SimulatorTransport`), der auf TesterPresent antwortet. Alles andere wäre ohne
Hardware eine Behauptung.

Daneben stehen zwei Transportwege, die noch **nicht** mit dem Bildschirm
verdrahtet sind: das eigene Drahtformat des C-Kerns über rohe CAN-Rahmen (Weg A)
und der Textwege zu Klasse-1-Dongles mit STN-Firmware (Weg B, Abschnitt
[Transport](#transport)).

## Aufbau

| Modul | Was es ist | Zweck |
| --- | --- | --- |
| `android/bindings` | JVM-Modul | kompiliert die erzeugten Bindings gegen den Host-Build des Kerns; die Linktests laufen hier |
| `android/app` | Android-App-Modul | Compose-Oberfläche, Selbsttest des Kerns, Transportwege zu BLE-/USB-Dongles |

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

Der erste Gradle-Lauf braucht **Netzzugang**: `:app` hängt an
`com.github.mik3y:usb-serial-for-android:3.11.0`, das nur über JitPack verteilt
wird. Das Repository steht dafür in `android/settings.gradle.kts`; ohne JitPack
scheitert die Abhängigkeitsauflösung, nicht der Code. Die Bibliothek (MIT) macht
Baudrate, Leitungsparameter und die Statusbytes der FTDI-Wandler — eigener
Treibercode dafür wäre fehleranfälliger und lizenzpflichtig.

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

Es gibt zwei Wege, und sie liegen **nebeneinander**, nicht übereinander. Der
Unterschied ist, wer ISO-TP macht: der Rust-Kern (Weg A) oder der Dongle
(Weg B).

| Weg | Wer segmentiert | Schnittstelle | Geräte |
| --- | --- | --- | --- |
| A — eigenes Drahtformat | Rust-Kern (`isotp.rs`) | `CanTransport` (CAN-Rahmen) | Geräte, die unser Rahmenformat sprechen |
| B — Textbefehle | der Dongle | `DiagnoseKanal` (fertige Diagnosenachricht) | Klasse-1-Dongles mit STN-Firmware (OBDLink & Co.) |

### Weg A — eigenes Drahtformat über CAN-Rahmen

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

Die Bytes werden nicht nur behauptet, sondern gegen den C-Code geprüft: ein
C-Programm baut dieselben Rahmen und `WireTest` vergleicht hexadezimal, inklusive
des Prüfwerts `CRC-16/CCITT-FALSE("123456789") = 0x29B1`.

### Weg B — Klasse-1-Dongles mit STN-Firmware

Klasse-1-Geräte machen ISO-TP **selbst**: Segmentierung, Flow-Control und
Wiederzusammensetzung passieren im Dongle, die App sieht nur Textzeilen. Damit
ist der Rust-Kern auf diesem Weg nicht beteiligt — deshalb steht `DiagnoseKanal`
neben `CanTransport` und nicht darunter (Begründung und Grenzen:
[Dongle-Matrix](vci-dongle-matrix.md) §1.1).

Der Ablauf ist der eines jeden ELM-kompatiblen Adapters:

```
ATZ   → Kennung        (Reset)
ATE0  → OK             (Echo aus: sonst steht der Befehl in der Antwort)
ATL0  → OK             (kein Zeilenvorschub)
ATS0  → OK             (keine Leerzeichen in Hex-Zeilen)
ATH1  → OK             (Header mit ausgeben — sonst ist die Antwort nicht zuzuordnen)
ATSP6 → OK             (Protokoll: 11-Bit-CAN, 500 kbit/s)
ATSH7E0 → OK           (Ziel-Header, nur bei Wechsel)
<Hex> → 7E8 <PCI> <Daten…>
```

| Datei | Aufgabe |
| --- | --- |
| `transport/DiagnoseKanal.kt` | zwei schmale Schnittstellen: `DiagnoseKanal` (Nachricht rein, Antwort raus) und `SerielleStrecke` (Text raus, Zeilen rein) |
| `transport/StnKanal.kt` | der Kanal selbst: Initialisierung, Header-Umschaltung, Fehlerzeilen (`?`, `NO DATA`, `CAN ERROR`, `BUFFER FULL`), Fristen; `StnAntwort` setzt Single-/First-/Folgerahmen zur Nutzlast zusammen |
| `transport/StnKennung.kt` | erkennt STN-Firmware über `STI` (`STN<Geräte-ID> vX.Y.Z`) — bewusst **nicht** über `ATI`, siehe unten |
| `transport/Zeilenpuffer.kt` | Zeilenbildung für Text-Dongles, gemeinsam für BLE und USB: puffert bis `\r`/`\n`, verwirft NUL-Bytes, behält den unfertigen Rest |
| `transport/BleSerielleStrecke.kt` | Textweg über BLE-GATT (NUS): Benachrichtigungen kommen asynchron auf einem Binder-Thread, `zeile` blockiert auf dem Aufrufer-Thread — Sperre, `wait`/`notifyAll`, Abbruch weckt sofort |
| `transport/UsbSerielleStrecke.kt` | Textweg über rohe Bulk-Endpunkte: USB hat keine Benachrichtigungen, hier wird in einer Schleife gelesen, bis die Zeile vollständig ist oder die Frist abläuft |
| `transport/UsbSerialStrecke.kt` | dieselbe Strecke für Wandler-Chips (`usb-serial-for-android`): `read`/`write` am `UsbSerialPort`, `IOException` wird zu „Gerät weg“ |
| `transport/FtdiStnVerbindung.kt` | USB-Kabelweg zu OBDLink EX/SX: Gerätesuche, Freigabedialog, Öffnen mit 115.200 Baud, dann `STI`-Prüfung — ohne STN-Kennung wird abgelehnt |

**Warum `STI` und nicht `ATI`:** STN-Geräte antworten auf `ATI` bewusst mit einem
ELM327-String („ELM327 v1.4b“) — laut STN-Handbuch die maximal vollständig
unterstützte ELM-Version, per `STSATI` sogar frei setzbar. `ATI` unterscheidet
also nichts. `STI` liefert dagegen `STN2256 v5.9.1`; ELM327-Clones kennen den
Befehl nicht und antworten mit `?`.

**Grenzen dieses Wegs:** Lesen und R1-Service. Kein R3, kein Flashen, kein
Security Access — Textbefehle sind dafür zu langsam und der Nachweis zu schwach.
Die STN-Prüfung ist ein **Filter, kein Beweis**: ein Clone, der `STI` nachahmt,
käme durch; unterstützt werden nur die Geräte aus der Dongle-Matrix, alles
andere ist ein Supportfall.

**Kabelweg nur FTDI:** `FtdiStnVerbindung` spricht ausschließlich Geräte mit
FTDI-VID (0x0403) an. CP210x, CH340 und PL2303 sind die Chips der
ausgeschlossenen ELM327-Clones und bleiben es. Der BLE-Weg setzt einen Dongle
voraus, der unser Textformat als Byte-Strecke über NUS transportiert.

### BLE-GATT

Der BLE-Weg ist derselbe Vorgang ohne Kabel: dieselben Rahmen (Weg A) bzw.
dieselben Textzeilen (Weg B), nur über eine Charakteristik geschrieben statt über
einen Bulk-Endpunkt. Standardprofil ist der **Nordic UART Service** (NUS), weil
er die verbreitetste transparente serielle Strecke über BLE ist; andere Profile
lassen sich über die UUIDs im Konstruktor von `BleGattVerbindung` setzen. Der
Scan filtert auf die Dienst-UUID, zeigt also keine Kopfhörer und Sensoren aus der
Werkstatt an.

Zwei Eigenheiten von BLE stecken im Transport:

1. **Benachrichtigungen kommen asynchron** auf einem eigenen Thread. `recv`
   wartet deshalb auf ein Signal (`wait`/`notifyAll`) statt zu pollen, und ein
   Verbindungsabbruch weckt es sofort — sonst hinge eine Diagnose bis zum Timeout.
2. **GATT erlaubt nur eine Schreiboperation zur Zeit.** `BleGattVerbindung`
   wartet auf die Quittung des Systems, bevor der nächste Rahmen geht, und
   schneidet längere Frames in 20-Byte-Stücke. Auf dem Textweg endet eine
   Benachrichtigung zusätzlich **nicht** an der Zeilengrenze: je nach MTU kommen
   20 Byte, ein `\r` fällt mitten in die nächste — dafür ist der `Zeilenpuffer` da.

## Was der Bildschirm zeigt

- **Kern geladen** — die Bibliothek lädt, `IsoTp()` lässt sich erzeugen; die
  Adressierung (0x7E0 → 0x7E8) kommt aus dem Kern, nicht aus Kotlin.
- **ISO-TP** — eine Anfrage geht durch Segmentierung, Transport und
  Wiederzusammensetzung zurück. Geprüft wird die Nutzlast, nicht nur „kein
  Absturz“.
- Fehler werden als `FEHLER` mit der Meldung des Kerns angezeigt, inklusive
  fehlender Bibliothek (`UnsatisfiedLinkError`).
- **Checker-Bericht** — ein Feld nimmt die Ausgabe von `vci-check --json` auf
  und zeigt Ergebnis-Satz, Kopfzeile (Gerät, Firmware, Resync-/CRC-Zähler) und
  jede Prüfung mit Statuswort und Handlungshinweis. Gelesen wird der Vertrag aus
  [vci-checker.md](vci-checker.md) §3 durch `VciBericht.ausJson` — die App
  entscheidet nichts nach und glättet auch keinen Widerspruch zwischen `verdict`
  und Einzelprüfung. Ein kaputter Bericht erscheint als Klartextmeldung, nicht
  als Absturz.
- **Bericht teilen** — derselbe Abschnitt legt den Bericht als Textdatei in
  `Downloads/Werkstatt VCI/` ab und öffnet den System-Dialog zum Teilen, damit
  die Werkstatt ihn an den Support schicken kann, ohne ihn abzutippen. Die
  Datei enthält oben die Auswertung, unten den unveränderten JSON-Bericht;
  Ablage über `MediaStore` statt `FileProvider`, weil `minSdk` 29 ist und der
  Pfad so ohne Provider im Manifest auskommt. Der Dateiname entschärft den
  Gerätenamen (nur `A–Z`, `a–z`, `0–9`, `-`, `_`, `.`), damit kein Gerät mit
  `/` im Namen den Pfad zerlegt.

Der Klasse-1-Pfad (Weg B) ist gebaut, aber **noch nicht** an den Bildschirm
gehängt: `MainActivity` kennt bisher nur `BleGattVerbindung` und den Simulator.
Bis dahin gilt für die Anzeige unverändert: ohne Dongle und Fahrzeug zeigt die
Prüfung auf dem Gerät ehrlich „kein USB-Dongle gefunden“ bzw. „kein Dongle
gefunden“.

## Geprüft / nicht geprüft

Geprüft: `:bindings:test` (drei Linktests gegen die echte Rust-Bibliothek).
`:app` enthält **83 `@Test`** in zehn Dateien — `VciCheck` 3, `DonglePruefung` 3,
`Wire` 9, `BleCanTransport` 7, `BleSerielleStrecke` 9, `UsbSerielleStrecke` 8,
`StnKanal` 15, `StnKennung` 10, `VciBericht` 13, `BerichtDatei` 6.

Davon sind **61 in dieser Umgebung tatsächlich gelaufen**: die sechs neueren
Testklassen (`StnKanal`, `StnKennung`, `BleSerielleStrecke`,
`UsbSerielleStrecke`, `VciBericht`, `BerichtDatei`) wurden mit `kotlinc 2.0.21`
gegen JDK 17 übersetzt und über `org.junit.runner.JUnitCore` gestartet — alle
grün. Die übrigen 22 wurden früher über `:app:testDebugUnitTest` ausgeführt; ein
Gradle-Lauf ist hier **nicht** möglich, weil in dieser Umgebung kein Android SDK
installiert ist. Ob die 83 Tests zusammen unter Gradle durchlaufen, ist damit
offen.

Ebenfalls geprüft (früher, mit NDK): `:app:assembleDebug` (Kompilierung,
Ressourcen, Manifest, Paketierung), APK-Inhalt (startbare Activity
`de.werkstatt.vci.MainActivity`, minSdk 29), die NDK-Übersetzung beider ABIs
(`readelf`: AArch64 bzw. X86-64, nur `libdl`/`libc` als Abhängigkeit — Bionic,
kein glibc), 29 exportierte `uniffi_vci_ffi_*`-Symbole darunter
`fn_constructor_isotp_new`, und dass `libvci_ffi.so` für `arm64-v8a` und
`x86_64` in der APK liegt.

**Nicht kompiliert:** `transport/UsbSerialStrecke.kt` und
`transport/FtdiStnVerbindung.kt`. Beide brauchen das Android SDK und
`usb-serial-for-android`; ein Probelauf mit dem Kotlin-Compiler dieser
Umgebung endet an unaufgelösten Symbolen (`android.*`, `com.hoho.*`,
`Context`). Sie sind geschrieben, aber kein Compiler hat sie bisher gesehen —
der erste `assembleDebug` auf einer Maschine mit SDK ist ihr erster echter Test.
Dasselbe gilt für die Erweiterung um den Checker-Bericht in `MainActivity.kt` und
für `BerichtTeilen.kt`: geprüft ist dort nur, dass beide Dateien syntaktisch
fehlerfrei parsen (Compilerlauf: keine Syntaxfehler, nur unaufgelöste Android-
und Compose-Symbole). Die Logik dahinter (`VciBericht`, `BerichtDatei`) ist
dagegen übersetzt und getestet.

Nicht geprüft: **Start auf einem Gerät oder Emulator**. In dieser VM ist
`/dev/kvm` für den Benutzer nicht zugänglich, ein Emulator wäre ohne
Hardwarebeschleunigung nicht lauffähig; echte Hardware ist nicht angebunden.
Damit ungeprüft bleiben: das `dlopen` aus `jniLibs` zur Laufzeit, die Anzeige
selbst, das Verhalten bei fehlender Bibliothek, **jede USB-Strecke**
(Gerätesuche, Freigabedialog, `bulkTransfer`, Baudrate am FTDI-Chip) und **jede
BLE-Strecke** (Scan, Verbindungsaufbau, Rechte-Dialog, Benachrichtigungen,
MTU-Verhalten, Abbruch bei Reichweitenverlust). Die JVM-Tests zeigen, dass
Format, Zeilenbildung, STN-Antwortzusammensetzung und Transportlogik stimmen —
nicht, dass ein echter OBDLink antwortet oder Bluetooth auf einem Tablet
funktioniert.

## Nächste Schritte

1. Gerätetest mit NDK-gebauter Bibliothek (arm64-v8a) — inklusive
   `UsbSerialStrecke`/`FtdiStnVerbindung` erstmals durch den Compiler.
2. Klasse-1-Pfad an den Bildschirm hängen: `FtdiStnVerbindung` öffnen,
   `STI`-Kennung anzeigen, einen lesenden Dienst (0x22 F190) fahren.
3. Erst danach VCI-Status und der erste schreibende Service am Fahrzeug.
