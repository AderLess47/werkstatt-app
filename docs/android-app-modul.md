# Android-App-Modul

Stand: zwei Bausteine in einem Modul — der **Selbsttest-Bildschirm** des Kerns und
die **Transportschicht** zu echten Dongles.

Der Bildschirm beantwortet genau eine Frage — **ist der Kern auf diesem Gerät
arbeitsfähig?** — und geht dabei den ganzen Weg vom Rust-Kern bis zum Pixel:
`libvci_ffi.so` → JNA → UniFFI-Bindings → Kotlin → Compose. Auf diesem
Bildschirm ist der Transport ein Simulator im Arbeitsspeicher
(`SimulatorTransport`), der auf TesterPresent antwortet. Alles andere wäre ohne
Hardware eine Behauptung.

Daneben stehen zwei Transportwege: das eigene Drahtformat des C-Kerns über rohe
CAN-Rahmen (Weg A) und der Textweg zu Klasse-1-Dongles mit STN-Firmware (Weg B,
Abschnitt [Transport](#transport)). Beide Wege hängen inzwischen am Bildschirm:
Weg B im Abschnitt „OBDLink (Klasse 1)“.

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
./android/gradlew -p android :app:assembleDebug
```

Gebaut wird über den Wrapper: `android/gradle/wrapper/gradle-wrapper.properties`
ist die einzige Stelle, an der die Gradle-Version steht — `./gradlew`, die CI und
`scripts/vorbereiten.sh` lesen sie dort.

```sh
./android/gradlew -p android :app:assembleDebug
```

Die `gradle-wrapper.jar` selbst liegt **nicht** im Repository (binär, rund 43 kB).
Fehlt sie, holt `./gradlew` sie beim ersten Lauf von einer festen Quelle und
prüft sie gegen die dort festgeschriebene SHA-256, bevor Java startet; passt der
Hash nicht, wird nichts ausgeführt und keine halbe Datei zurückgelassen. Das
kostet beim allerersten Lauf Netzzugang — ohne Netz und ohne Jar bricht der
Aufruf mit einer Meldung ab, statt in einen Klassenfehler zu laufen. Liegt eine
Jar mit anderem Hash vor, warnt der Wrapper nur, damit ein bewusst neu erzeugter
Wrapper nicht blockiert.

Kommitten lohnt sich, sobald auf Rechnern **ohne** Netzzugang gebaut werden soll
oder die Herkunft der Datei nicht von GitHub abhängen darf: dann entfällt der
Download, und die Jar reist mit dem Klon.

```sh
./android/gradlew --version           # legt gradle/wrapper/gradle-wrapper.jar an
git add gradle/wrapper/gradle-wrapper.jar
```

Die Tests von `:app` brauchen weder Gradle noch ein Gerät:

```sh
sh scripts/jvm-tests.sh
```

Das Skript übersetzt Bindings, Haupt- und Testcode mit dem Kotlin-Compiler aus dem
Gradle-Cache gegen JDK 17 und startet JUnit auf der JVM; die native Bibliothek
kommt aus dem Host-Build (siehe [Geprüft / nicht geprüft](#geprüft--nicht-geprüft)).

Der erste Gradle-Lauf braucht **Netzzugang**: `:app` hängt an
`com.github.mik3y:usb-serial-for-android:3.10.0`, das nur über JitPack verteilt
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
./android/gradlew -p android :app:assembleDebug
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
| `transport/UsbDongleVerbindung.kt` | Gerätesuche, USB-Freigabe per Broadcast, Öffnen, Prüflauf; `oeffnen()` gibt den offenen Transport einzeln heraus |
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

### Wartezeiten

Alle Fristen sind **Obergrenzen, nach denen die App aufgibt** — keine
Protokollfristen. Jede steht als benannte Konstante an ihrer Stelle im Code, mit
derselben Begründung wie hier:

| Ort | Wert | Gilt für | Herleitung |
| --- | --- | --- | --- |
| `UsbAbschnitt` (`IsoTp.recv`) | 1 500 ms **je Rahmen** | Weg A: First Frame, dann jeder Folgeantwort-Rahmen | ISO 15765-2 setzt N_Cr = 1 000 ms; +50 % Reserve für Bulk-Transfer und Scheduler |
| Kern `IsotpChannel::send` | 1 000 ms fest | Weg A: Warten auf Flow-Control bei Anfragen über 7 Byte | entspricht N_Bs (ISO 15765-2); im Kern verdrahtet, von der App nicht einstellbar |
| `DonglePruefung` | 800 ms | TesterPresent (Single Frame) | ISO 14229-2: P2_server = 50 ms für die erste Antwort; Reserve für den ersten Transfer nach dem Öffnen |
| `StnKonfiguration.antwortTimeoutMs` | 3 000 ms | Weg B: eine ganze Antwort | der Dongle segmentiert selbst und entscheidet mit `ATST` (Werkseinstellung `ST = 32`, rund 200 ms laut ELM327-Datenblatt); Buszeit für drei Rahmen < 1 ms |
| `StnKonfiguration.startTimeoutMs` | 2 000 ms | Weg B: `ATZ` und die `AT`-Einstellungen | `ATZ` (Reset) ist der langsamste Befehl, alles danach antwortet sofort `OK` |
| `StnKennung` | 1 500 ms | `STI` | eine Textzeile; Obergrenze, nicht Erwartungswert |
| `UsbDongleVerbindung` / `FtdiStnVerbindung` | 30 000 ms | USB-Freigabedialog | menschliche Reaktion am Gerät, kein Protokoll |
| `P2_STERN_MS` / `StnKonfiguration.p2SternTimeoutMs` | 5 000 ms **je Aufschub** | beide Wege: Warten nach einer Negative Response 0x78 | ISO 14229-2: P2\* = 5 000 ms; höchstens `MAX_AUFSCHUB` = 3 Aufschübe, also 15 s zusätzlich |
| `SicherheitAbschnitt` (`SICHERHEIT_FRIST_MS`) | 5 000 ms | `27` je Richtung: Seed fragen, Schlüssel schicken | zwei kurze Nachrichten, beide ein einzelner Rahmen; P2 (50 ms) plus Reserve für einen trägen Dongle — für 0x78 verlängert der Kern selbst |

**Wichtig für Weg A:** die 1 500 ms gelten **je Rahmen**. `IsotpChannel::recv`
reicht die Frist an jedes `recv` weiter; eine Antwort über 20 Byte (First Frame
plus zwei Folgeantworten) darf also bis 4,5 s brauchen, bevor sie als Fristablauf
zählt. Das ist bewusst großzügiger als ein einzelner TesterPresent (800 ms), weil
hier drei Rahmen ankommen statt einem.

**Die Fristen sind Obergrenzen für den Abbruch, keine Erwartungswerte:** eine
Antwort auf `22 F190` mit P2_server = 50 ms ist nach 50–200 ms da; die Frist wird
nur gebraucht, wenn etwas fehlt — dann steht auf dem Bildschirm ein Fehler statt
einer Wartezeile, die nie endet.

**0x78 („response pending“) wird auf beiden Wegen behandelt.** `7F <Dienst> 78`
ist keine Ablehnung, sondern ein Aufschub: das Steuergerät hat die Anfrage
angenommen und rechnet noch (typisch bei Routinen). Dann wird **nicht neu
gefragt** — nach ISO 14229-1 kommt die Antwort von selbst — sondern erneut
gewertet:

- **Weg A:** `UsbAbschnitt` ruft `IsoTp.recv` mit `P2_STERN_MS` erneut auf; der
  Kern liest die endgültige Antwort als eigene ISO-TP-Nachricht.
- **Weg B:** `StnKanal` filtert die 0x78-Zeilen aus der Zeilenfolge
  (`StnAntwort.ohneAufschub`) und liest mit `p2SternTimeoutMs` weiter, bis eine
  Zeile ohne Aufschub da ist. Der Prompt `>` beendet weiterhin jede Runde, und
  der Kanal bleibt offen — nach 0x78 wird nur gelesen, nie erneut gesendet.

Höchstens drei Aufschübe, dann steht ein Fehler statt einer endlosen Wartezeile;
die Wartezeile nennt den Aufschub samt Frist, damit ein Abbruch zuordenbar bleibt.

**Grenze auf Weg B:** dort entscheidet der Dongle mit `ATST`, wie lange er
überhaupt zuhört (Werkseinstellung rund 200 ms). Kommt die endgültige Antwort
später, hat er das Zuhören beendet und meldet `NO DATA` — dann steht eine
Zeitüberschreitung, und die Ursache ist die Adaptereinstellung, nicht das
Steuergerät. Die App setzt `ATST` bewusst nicht selbst hoch: sonst würde sie auch
jeden echten Ausfall 5 s lang aussitzen.

**Nicht geprüft:** die 0x78-Behandlung ist mit aufgezeichneten Zeilen geprüft
(`StnKanalTest`), aber auf **keinem Gerät** und mit **keinem Steuergerät**, das
wirklich 0x78 sendet. Auf Weg A hängt sie am Verhalten von `IsotpChannel::recv`,
das hier nur über die Rust-Bibliothek erreichbar ist.

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
- **Eigenes Drahtformat (Weg A)** — „USB-Dongle öffnen“ holt Freigabe und Gerät
  (`UsbDongleVerbindung.oeffnen`), „Dienst 22 F190 lesen“ schickt die Kennung
  durch `IsoTp` über `UsbCanTransport`; gelesen wird die Antwort mit demselben
  `DienstAntwort` wie auf dem Klasse-1-Weg, damit sich beide Wege vergleichen
  lassen. Der Knopf „USB prüfen“ bleibt daneben der Schnelltest (Bordspannung,
  TesterPresent) und speist den Selbsttest-Bericht.
- **OBDLink am Kabel (Klasse 1)** — eigener Abschnitt am Bildschirm: „OBDLink
  öffnen“ sucht ein FTDI-Gerät, holt die USB-Freigabe, öffnet mit 115.200 Baud
  und fragt `STI`; erst wenn dort `STN…` steht, gilt der Dongle als
  angesprochen. Danach liest „Dienst 22 F190 lesen“ einen lesenden Dienst und
  zeigt Größe, Hex und den druckbaren Text. Die Auswertung steckt in
  `DienstAntwort` (Hex, ASCII, Negative Response Codes im Klartext), damit sie
  ohne Gerät prüfbar bleibt.
- **Selbsttest-Bericht ohne Eingabe** — der Knopf „Selbsttest-Bericht teilen“
  steht direkt bei der Dongle-Prüfung und braucht nichts Eingefügtes: er nimmt
  die Kern- und Dongle-Befunde, die ohnehin auf dem Bildschirm stehen, dazu den
  zuletzt angekündigten Warteschritt („Zuletzt gewartet auf“), und schreibt
  daraus `vci-selbsttest-<gerät>-<jjjjmmtt-hhmm>.txt` (gleiche Ablage,
  gleicher Teilen-Dialog). Die Datei sagt selbst, was sie ist: Selbsttest der
  App auf diesem Telefon, **nicht** der Bericht von `vci-check --json` —
  zwei Berichte, zwei Wahrheiten, nicht vermischt. Ist die Dongle-Prüfung noch
  nicht gelaufen, steht dort „nicht gelaufen“ statt einer erfundenen Messung.
- **SFD (Schutz Fahrzeug Diagnose)** — eigener Abschnitt (`SfdAbschnitt.kt`) für
  Status (`22 01 74`), Challenge (`31 01 C0 04`), Token (`31 01 C0 08`) und
  Sperren (`31 01 C0 09`), jeweils mit Rohantwort neben der Deutung. Der
  **Token wird nirgends gespeichert**: er steht ausschließlich im Textfeld
  dieses Abschnitts (Compose-Zustand im Arbeitsspeicher), geht von dort in die
  Anfrage und ist danach weg. Kein Pref, keine Datei, kein Selbsttest-Bericht,
  kein Eintrag in der Audit-Kette — für die Kette prüft das ein Test, der
  `als_json()` nach dem Token durchsucht.

  Die Entscheidung dahinter: ein Token ist ein Geheimnis mit kurzer Gültigkeit,
  das aus einem Herstellerportal kopiert kommt. Was die App nicht ablegt, kann
  sie nicht verlieren, nicht versehentlich in einem Support-Ticket
  mitschicken und nicht über eine Gerätesicherung auf einen fremden Rechner
  bringen. Ein „Token-Cache“ würde den nächsten Unlock bequemer machen und
  genau dafür sorgen, dass ein abgelaufener oder falscher Token irgendwann aus
  dem Nichts auftaucht — bei einem Vorgang, der ein Steuergerät öffnet, ist
  Bequemlichkeit der falsche Maßstab.

  Zwei ehrliche Einschränkungen: der Text bleibt im Arbeitsspeicher stehen,
  solange der Bildschirm existiert (Kotlin-Strings lassen sich nicht
  überschreiben), und was der Bediener vorher in die Zwischenablage kopiert
  hat, gehört dem System, nicht der App — sie liest sie nur auf Knopfdruck (in
  die Zwischenablage schreibt sie ausschließlich die empfangene Challenge,
  nie den Token).
- **Sicherheitszugriff (27)** — eigener Abschnitt (`SicherheitAbschnitt.kt`), die
  Logik daneben in `SicherheitBedienung.kt`: Ebene als Chips (1, 3, 5) oder frei
  eingegeben, der **eingetragene** Schlüssel, darunter eine Karte mit Ebene, Seed
  und Ergebnis. Die Ebenen-Prüfung liest Hex und lehnt gerade Zahlen ab — `27 02`
  ist die Schlüssel-Unterfunktion, keine Ebene, und ein Senden dorthin endet am
  Steuergerät als Längenfehler. Der **Schlüssel wird eingetragen, nicht
  gerechnet**: ein Seed/Key-Algorithmus liegt bewusst nicht im Repository
  (Plan §1.1), er kommt aus einem Portal oder einem lizenzierten Baustein.
  Ohne eingetragenen Schlüssel geht nur die Seed-Anfrage raus; der Kern meldet
  den fehlenden Schlüssel, und der Hinweis zeigt auf das Eingabefeld statt auf
  Kabel und Zündung — ein geratener Schlüssel wäre am Steuergerät ein
  Fehlversuch mit Wartezeit (NRC 0x36/0x37). Der Seed steht so da, wie er auf
  dem Bus lag, und leer bzw. lauter Nullbytes werden **benannt** („Ebene war
  bereits offen“): ein „00 00“ ohne diesen Satz würde als Challenge
  missverstanden. „Bereits offen“ und „entsperrt“ sind zwei verschiedene
  Ergebnisse und stehen deshalb als zwei Sätze da.
- **Service-Funktionen** — eigener Abschnitt (`FunktionenAbschnitt.kt`) mit den
  fünf Kataloginhalten aus `content/funktionen`: Risikoklasse als Wort (`R1`,
  `R2`), die Vorbedingungen mit **gemessener** Bordspannung („12,30 V (Soll
  11,0–15,0 V)“ bzw. „nicht messbar“), die geplanten Schritte und nach dem Lauf
  eine Zeile je Schritt („1. 10 03 → Erfolg“ / „… → nicht gesendet“).
  Die Schritte entstehen aus der Audit-Kette statt aus einem Fortschritts-Rückruf
  — der Kern meldet jeden Schritt genau einmal, und eine zweite Meldung wäre
  eine zweite Wahrheit. Während des Laufs steht deshalb „läuft …“ da.
  Gesperrte Inhalte (`freigabe: unbelegt`) zeigt die App mit dem Grund aus
  `pruefe_freigabe`; sie sendet dann keinen Rahmen. Die YAML-Dateien liegen
  **einmal** im Repo und werden beim Bauen in die Assets kopiert (Gradle-Task
  `katalogKopieren`), damit im APK derselbe Stand steckt, den der Kern prüft.
- **Audit-Kette** — eigener Abschnitt (`AuditAbschnitt.kt`) über derselben
  `Audit`-Kette wie die Funktionsabläufe: Prüfbefund aus `pruefen()` („Kette in
  Ordnung: 3 Einträge, letzter Hash…“ / „Kette gebrochen: …“), eine Zeile je
  Eintrag mit Vorgängerhash, „Als Text kopieren“ und „Kette als Datei teilen“
  (Kopf mit Gerät, Zeitpunkt, Prüfbefund, darunter die Kette als JSON).
  In dieselbe Kette schreibt auch der SFD-Pfad: Entsperren (`31 01 C0 08`) und
  Sperren (`31 01 C0 09`) hängen ihren Ausgang an, sobald der Bus geantwortet
  hat; der Abschnitt zeichnet danach neu. Der Eintrag enthält den Zeitpunkt,
  das Fahrzeug, die Anfrage-Kennung des Kanals (`0x7E0` oder die gewählte),
  den Dienst im Klartext („SFD entsperren 31 01 C0 08“) und das Ergebnis — bei
  einer Ablehnung den Klartext-NRC, etwa „Fehler: abgelehnt (Dienst 0x31, NRC
  0x35: invalidKey)“. Auch der Fehlversuch wird protokolliert: genau den will
  man später erklärt haben.

  Bewusst **nicht** im Eintrag steht der Token. Der Nachweis wird
  weitergegeben — an den Support, als Datei, in ein Ticket — und ein Token
  darin wäre ein weitergegebenes Geheimnis. Steht nur der Dienst drin, belegt
  der Eintrag den Vorgang, ohne den Schlüssel zu verraten; ein Test prüft das
  (`als_json()` enthält den Token nicht). Aus demselben Grund steht als
  Fahrzeug „unbekannt“, solange die App keine VIN kennt — eine erfundene
  Nummer wäre schlimmer als eine ehrliche Lücke.

  Die Kette ist dabei keine Option, die man vergessen kann: sie geht beim
  Bauen des `Sfd`-Objekts mit (`Sfd.withIds(audit, vin, …)`), es gibt also
  keinen Aufruf, der entsperrt und nichts protokolliert. Der Zeitstempel
  entsteht an der Sprachgrenze, weil der Kern die Zeit übergeben bekommt und
  dadurch ohne Uhr prüfbar bleibt.

Während des Wartens nennt jeder Abschnitt den Schritt (`schritt`-Zustand im
Composable, kein blosses „Läuft …“) — etwa „Warte auf die Antwort auf 22 F190 —
Frist 1,5 s je Rahmen“. Beim Abbruch wiederholt der Befund denselben Schritt
(„beim Warten auf die Antwort abgebrochen: …“), damit der Selbsttest-Bericht
erkennen lässt, wobei es gehangen hat. Dieselbe Zeile führt die Dongle-Prüfung in
`MainActivity.kt` („Suche Bluetooth-Dongle (bis 10 s) und verbinde (bis 15 s) …“).

Beide Wege stehen damit als eigene Abschnitte am Bildschirm: `ObdLinkAbschnitt.kt`
hängt `FtdiStnVerbindung` (FTDI-Kabel), `StnKennung` (`STI`) und `StnKanal` an
die Oberfläche, `UsbAbschnitt.kt` den rohen USB-Weg über `UsbDongleVerbindung`
und `IsoTp`. Keine stille Umschaltung: der eine Knopf entscheidet nicht über den
anderen, und was ein Weg nicht kann, steht im Klartext daneben. Ohne Dongle und
Fahrzeug zeigt die Prüfung auf dem Gerät weiter ehrlich „kein USB-Dongle
gefunden“ bzw. „kein Dongle gefunden“.

## Geprüft / nicht geprüft

Geprüft: `:bindings:test` (drei Linktests gegen die echte Rust-Bibliothek).
`:app` enthält **185 `@Test`** in einundzwanzig Dateien — `VciCheck` 3,
`DonglePruefung` 3, `Wire` 9, `BleCanTransport` 7, `BleSerielleStrecke` 9,
`UsbSerielleStrecke` 8, `StnKanal` 18, `StnKennung` 10, `Zeilenpuffer` 11,
`VciBericht` 13, `BerichtDatei` 6, `AppBericht` 10, `DienstAntwort` 8, `Frist` 5,
`SfdBedienung` 32, `SicherheitBedienung` 6, `SfdFfi` 7, `SicherheitFfi` 5, `FunktionFfi` 3, `ServiceFunktionen` 7,
`AuditAnzeige` 4.

Alle **185 sind in dieser Umgebung gelaufen** — `sh scripts/jvm-tests.sh` endet
mit `OK (185 tests)`. Die letzten fünf Klassen sind Linktests gegen die echte
Rust-Bibliothek: sie prüfen die erzeugten Bindings, nicht nur Kotlin-Code. Übersetzt wird mit `kotlinc 2.0.21` gegen JDK 17,
gestartet über `org.junit.runner.JUnitCore`.

Dafür brauchte es zwei Handgriffe, die das Skript festhält:

1. **Die Bindings werden mit übersetzt** (`-jvm-target 17`). Die erzeugte
   `vci_ffi.kt` ist auf Java 21 eingestellt; ein älteres JDK kann die
   Klassendateien sonst nicht laden (`UnsupportedClassVersionError`, 65.0).
2. **Geladen wird der Host-Build der Bibliothek**, nicht der aus `jniLibs`. Die
   Datei für Android verlangt `libdl.so` und Symbole der Version `LIBC` — auf
   glibc heißt die Version `GLIBC_2.2.5`, `dlopen` scheitert also unabhängig von
   den Tests. `core-ffi/target/release/libvci_ffi.so` aus
   `cargo build --release` ist derselbe Rust-Code für diese Plattform und lässt
   sich laden. Damit laufen `VciCheck`, `DonglePruefung` und `BleCanTransport`
   gegen den **echten Kern**, nicht gegen eine Attrappe.

Ein Gradle-Lauf ist inzwischen **gelaufen** — JDK 17, Gradle 8.9, SDK 34, NDK
26.1.10909125: `./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug`
endet mit `BUILD SUCCESSFUL`, und `:app:testDebugUnitTest` sieht dieselben 185
Tests grün wie `scripts/jvm-tests.sh`. Geprüft ist damit auch die
Gradle-Verdrahtung.

Ebenfalls geprüft (früher, mit NDK): `:app:assembleDebug` (Kompilierung,
Ressourcen, Manifest, Paketierung), APK-Inhalt (startbare Activity
`de.werkstatt.vci.MainActivity`, minSdk 29), die NDK-Übersetzung beider ABIs
(`readelf`: AArch64 bzw. X86-64, nur `libdl`/`libc` als Abhängigkeit — Bionic,
kein glibc), 29 exportierte `uniffi_vci_ffi_*`-Symbole darunter
`fn_constructor_isotp_new`, und dass `libvci_ffi.so` für `arm64-v8a` und
`x86_64` in der APK liegt.

**Mit echtem Compose kompiliert, nicht erprobt:** `transport/UsbSerialStrecke.kt`
und `transport/FtdiStnVerbindung.kt` brauchen das Android SDK und
`usb-serial-for-android`. Vor dem ersten gelungenen `assembleDebug` endete ein
Probelauf an unaufgelösten Symbolen (`android.*`, `com.hoho.*`, `Context`); seit
SDK, NDK und Gradle da sind, gehen beide durch den echten Compiler. Ein Compiler
sagt nichts über das Verhalten am Dongle.

Für `UsbAbschnitt.kt` und `ObdLinkAbschnitt.kt` gibt es inzwischen mehr als eine
Syntaxprobe: `UsbAbschnitt.kt` wurde gegen **Attrappen der Compose-API**
übersetzt (Compiler-Exit 0, inklusive der 0x78-Schleife) — das prüft Typen und
Logik, nicht das Verhalten von Compose. Die Fristformatierung ist dabei in
`Frist.kt` gewandert und wird dort auf der JVM getestet (`FristTest`), weil sie
ohne Bildschirm prüfbar ist. **Die beiden Wege sind verdrahtet, aber nicht
erprobt** — kein FTDI-Gerät, kein VCI, kein Fahrzeug, kein Emulator in dieser
Umgebung. Die Wartezeiten sind aus Normwerten hergeleitet (siehe
[Wartezeiten](#wartezeiten)), aber **nicht am Bus nachgemessen**.

`SicherheitAbschnitt.kt` übersetzt seit dem ersten `:app:compileDebugKotlin`
mit (Exit 0, gegen SDK 34) — damit sind Typen und Aufrufe geprüft, nicht die
Anzeige. `SicherheitBedienung.kt` läuft dagegen auf der JVM: **sechs** Tests
(Ebenen-Prüfung, Anbieter, Seed-Texte, Meldung ohne Schlüssel). Was fehlt, ist
der Ablauf am Gerät: kein Steuergerät, das auf `27 01` antwortet, und kein
Schlüssel — ohne ihn geht nur die Seed-Anfrage raus.

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
2. Klasse-1-Weg am Gerät erproben: `STI`-Kennung eines echten OBDLink EX/SX
   anzeigen und `22 F190` am Fahrzeug lesen — der Knopf ist da, die Hardware
   fehlt.
3. Erst danach VCI-Status und der erste schreibende Service am Fahrzeug.
