# J2534-DLL-Port — Spezifikation

**Stand:** 14.11.2026 · Code: [`j2534/`](../j2534) · Grundlage: [werkstatt-app-plan.md](werkstatt-app-plan.md) §5
**Status:** ABI-Schicht **und** Transport/ISO-TP implementiert und getestet (16 Exporte, **46 Abnahmeprüfungen grün**, simuliertes Gerät). **WinUSB-Adapter geschrieben, aber auf diesem Host nicht kompilierbar; keine echte Hardware angebunden.**

---

## 1. Warum eine eigene DLL?

Drei Gründe, in dieser Reihenfolge:

1. **Eine API für alle VCIs.** Ob eigener Dongle, Hersteller-SDK oder Gateway im LAN — die App spricht immer dieselbe Schnittstelle. Neue Hardware ist ein Transport-Austausch, kein Umbau der Fahrzeuglogik.
2. **OEM-Software kann unsere Hardware nutzen.** Eine korrekt registrierte PassThru-DLL erscheint in ODIS, ISTA, Techstream & Co. als Gerät. Das ist ein Verkaufsargument für die Hardware und ein Testwerkzeug für uns.
3. **Testbarkeit ohne Fahrzeug.** Der Loopback-Transport erlaubt vollständige Abnahmetests im CI — genau das, was in diesem Repository bereits läuft.

**Was eine DLL nicht löst:** Sie ersetzt keine Lizenz. Sie macht unsere Hardware kompatibel, nicht unsere App zum Herstellertool (§1.1 des Plans gilt unverändert).

---

## 2. Was „Port" hier konkret heißt

| Plattform | Artefakt | Status |
|---|---|---|
Windows x64 | `j2534.dll` (MSVC), Registry-Eintrag | Quellen + Exportliste vorhanden, Build erfordert MSVC/MinGW |
Linux x64 | `libj2534.so` | gebaut, 46 Abnahmeprüfungen grün (`make test`) |
macOS | `libj2534.dylib` | gleiche Quellen, nicht gebaut |
Android / iOS | **kein J2534** | dort direkt die Rust-API; kein DLL-Loading, kein Bedarf |

Der ABI-Header verwendet durchgehend `uint32_t`/`int32_t` statt `unsigned long`. Grund: In der Norm sind die Felder `unsigned long`, das ist auf Win32/x64 32 Bit, auf Linux/macOS aber 64 Bit — die Strukturen würden sonst unterschiedlich liegen und die DLL stillschweigend falsche Werte lesen.

---

## 3. ABI-Fläche (16 Funktionen)

| Funktion | Semantik | Fehlerfälle im Code |
|---|---|---|
`PassThruOpen` | Gerät öffnen, DeviceID liefern | `ERR_NULL_PARAMETER`, `ERR_DEVICE_IN_USE` |
`PassThruClose` | Gerät schließen, Kanäle freigeben | `ERR_INVALID_DEVICE_ID` |
`PassThruConnect` | Kanal über Protokoll öffnen | `ERR_INVALID_PROTOCOL_ID`, `ERR_INVALID_BAUDRATE`, `ERR_EXCEEDED_LIMIT` |
`PassThruDisconnect` | Kanal schließen | `ERR_INVALID_CHANNEL_ID` |
`PassThruReadMsgs` | Nachrichten lesen; bei leerem Puffer **0 Nachrichten + `STATUS_NOERROR`** | `ERR_INVALID_CHANNEL_ID`, `ERR_NULL_PARAMETER` |
`PassThruWriteMsgs` | Nachrichten senden, Anzahl zurückgeben | `ERR_INVALID_MSG` bei Länge 0 |
`PassThruStartPeriodicMsg` / `Stop` | periodisch senden | `ERR_NOT_SUPPORTED` (bewusst offen) |
`PassThruStartMsgFilter` | PASS/BLOCK/FLOW_CONTROL-Filter | `ERR_INVALID_FLAGS`, `ERR_NO_FLOW_CONTROL` |
`PassThruStopMsgFilter` | Filter entfernen | `ERR_INVALID_FILTER_ID` |
`PassThruSetProgrammingVoltage` | Programmier-Spannung an Pin | **`ERR_NOT_SUPPORTED`** — Sicherheit vor Funktionsumfang |
`PassThruReadVersion` | Firmware-/DLL-/API-Version | `ERR_NULL_PARAMETER` |
`PassThruGetLastError` | Klartext zur letzten Fehlernummer | — |
`PassThruIoctl` | GET/SET_CONFIG, READ_VBATT, CLEAR_* | `ERR_INVALID_IOCTL_ID`, `ERR_INVALID_IOCTL_VALUE` |
`PassThruScanForDevices` / `GetNextDevice` | API 05.00, dynamische Erkennung | `ERR_BUFFER_EMPTY` am Listenende (siehe §9) |

Verifiziert: `nm -D` zeigt **16** Symbole, identisch mit [`win/j2534.def`](../j2534/win/j2534.def).

---

## 4. Registry-Registrierung (Windows)

**API 04.04**
```
HKLM\SOFTWARE\PassThruSupport.04.04\<Vendor> - <Device>
    Vendor            REG_SZ   Herstellername
    Name              REG_SZ   Gerätename (erscheint in der App-Auswahlliste)
    FunctionLibrary   REG_SZ   voller Pfad zur DLL, max. 260 Zeichen, ASCII
    ConfigApplication REG_SZ   optional, Konfigurationsprogramm
    CAN               REG_DWORD  Anzahl gleichzeitiger Kanäle (0 = nicht unterstützt)
    ISO15765          REG_DWORD
    J1850PWM          REG_DWORD
    J1850VPW          REG_DWORD
    ISO9141           REG_DWORD
    ISO14230          REG_DWORD
    SCI_A_ENGINE / SCI_A_TRANS / SCI_B_ENGINE / SCI_B_TRANS   REG_DWORD
```
**API 05.00** derselbe Aufbau unter `HKLM\SOFTWARE\PassThruSupport.05.00`; Anwendungen, die 05.00 sprechen, sehen nur dort registrierte DLLs — und umgekehrt. Wer beides will, registriert zweimal.

**Fallstricke, die Zeit kosten:**
- 32-Bit-Anwendungen lesen über `WOW6432Node`; für 32-Bit-Ziele separat registrieren bzw. 32-Bit-DLL bereitstellen.
- Alle Werte ASCII, `Name`/`Vendor` dürfen nicht mit `\0` beginnen.
- Nur Kanäle eintragen, die wirklich funktionieren. Ein zu hoch angegebener Wert führt bei fremder Software zu Fehlern, die wie Fahrzeugprobleme aussehen.

---

## 5. Zustandsmaschine

```
CLOSED ──Open──► OPEN ──Connect──► CONNECTED ──Disconnect──► OPEN
                   │                   │  ▲
                   │                   ├──StartMsgFilter──► FILTERED (bis Stop/Clear)
                   │                   └──StartPeriodic──► PERIODIC (bis Stop/Clear)
                   └──Close──► CLOSED

Regeln:
  • Close gibt alle Kanäle frei, ohne Fehler zu melden (kein Leck).
  • Jede Funktion prüft Handle und Zustand, bevor sie etwas tut.
  • Kein globaler Lock über einen blockierenden Transport-Aufruf.
```

---

## 6. Threading und Timing

- **Thread-safe:** ja, über einen Mutex je Kanal; Lesen und Schreiben dürfen nicht gegeneinander blockieren.
- **ReadMsgs:** blockiert bis `Timeout` ms, liefert bis zu `*pNumMsgs` Nachrichten, danach `*pNumMsgs = 0` und `STATUS_NOERROR`. Ein `ERR_TIMEOUT` an dieser Stelle ist eine der häufigsten Ursachen für „Tool reagiert nicht".
- **P2/P2\* (50/5000 ms) und ISO-TP `BS`/`STMIN`** sind Sache des Protokollstacks, nicht der DLL. Die DLL reicht die Parameter durch (`SET_CONFIG`) und hält das Zeitverhalten der Busschicht ein.
- **`0x78 responsePending`** verlängert P2\* — der Stack muss die Zeit nachführen, sonst brechen lange Routinen (DPF-Regeneration) scheinbar grundlos ab.
- **Kein `Sleep` in der DLL** außer im Wartepfad von `ReadMsgs`; alles andere ist Polling mit Deadline.

---

## 7. Transport-HAL

Die DLL kennt nur vier Transportfamilien. Alles andere ist ein Adapter:

| Transport | Technik | Anmerkung |
|---|---|---|
USB | **WinUSB/libusb** | **kein Kernel-Treiber, kein WHQL** — das ist der wichtigste Aufwandshebel: keine Treiberzertifizierung, keine Blue-Screens |
BLE | GATT-Bridge zum Dongle | für iOS/Android-Seite identisch nutzbar |
TCP/IP | DoIP-kompatibles Gateway im LAN, Firmware-Update | deckt OEM-Passthru über den Werkstatt-Rechner ab |
Hersteller-SDK | dünner Adapter auf `Transport` | wenn ein VCI-Hersteller keine offene Schnittstelle bietet |

Firmware-Update der Hardware läuft **nicht** über die J2534-API, sondern über ein eigenes, signiertes Update-Werkzeug (sonst kann fremde Software die Hardware umflashen).

### 7.1 Protokoll zum Gerät

Was zwischen DLL und VCI auf der Leitung liegt, ist nicht standardisiert — das ist unser eigenes Protokoll und damit unser Fehlerrisiko. Deshalb bewusst schlicht und selbstprüfend:

```
Rahmen:  0xAA 0x55 | Typ (1) | Länge (1) | Nutzlast (0..255) | CRC-16/CCITT (2, LE)
         CRC über Typ + Länge + Nutzlast

Typ 0x01  CAN-Frame:    [ID u32 LE][DLC u8][8 Byte Daten]
Typ 0x02  Spannung:     Antwort [mV u32 LE]
Typ 0x03  Version
Typ 0x7F  Fehler
```

Der Bulk-Endpunkt liefert beliebige Teilstücke: ein Rahmen kann über mehrere `read()`-Aufrufe kommen, mehrere Rahmen in einem Aufruf stecken, und nach einem Kabelwackler können Müllbytes davor liegen. Die Schicht puffert bis 4 KiB, sucht die Sync-Folge, verwirft unplausible Längen und zählt Resyncs und CRC-Fehler — beides ist im VCI-Checker sichtbar, weil es der erste Hinweis auf ein Hardwareproblem ist.

**ISO-TP liegt bewusst hier, nicht im Aufrufer:** Bei `ProtocolID = ISO15765` erwartet die J2534-API die vollständige Diagnosenachricht; die Segmentierung nach ISO 15765-2 gehört in die DLL. Implementiert sind Single Frame, First Frame mit Flow-Control-Aushandlung, Consecutive Frames mit Sequenzprüfung und die Annahme fremder Flow-Control-Rahmen.

---

## 8. Was heute funktioniert — und was nicht

| Baustein | Status |
|---|---|
ABI-Schicht, Zustandsmaschine, Fehlercodes | **implementiert**, 26 Tests |
Filterverwaltung, Config-IOCTL, VBATT, Version | **implementiert**, getestet |
Negativfälle (doppeltes Öffnen, falsches Protokoll, Länge 0, unbekannter IOCTL) | **implementiert und getestet** |
Framing (CRC-16, Resynchronisation, Teilstücke) | **implementiert**, 20 Tests |
ISO-TP Segmentierung (SF/FF/CF/FlowControl, beide Richtungen) | **implementiert**, inkl. 20-Byte-Nachrichten |
Fehlerpfade: Müllbytes, CRC-Fehler, Timeout, Kabelziehen | **implementiert und getestet** |
**WinUSB-Adapter** (`src/usb_winusb.c`) | **geschrieben, nicht kompiliert** — Windows SDK auf diesem Host nicht verfügbar |
Discovery (05.00) | simuliert (ein Gerät) |
Periodisches Senden | nicht implementiert (`ERR_NOT_SUPPORTED`) |
Programmier-Spannung | nicht implementiert (bewusst) |
BLE- und TCP/DoIP-Transport | offen |
Registry-Installer, INF, 32-Bit-Build | offen |

---

## 9. Offene Punkte, die vor einem Release geklärt werden müssen

1. **Normtext kaufen.** SAE J2534-1/-2 ist kostenpflichtig. Die Konstanten für `SET_CONFIG`-Parameter (P1…P4, W1…W5, `ISO15765_BS_TX`, `ISO15765_STMIN_TX`, `WFT_MAX`) und der Fehlercode für „Liste erschöpft" in 05.00 sind hier bewusst nur teilweise gesetzt. Bis zur Verifikation liefert `GetNextDevice` am Listenende `ERR_BUFFER_EMPTY`.
2. **Konformität nachweisen.** Es gibt keinen öffentlichen J2534-Zertifizierungstest. Nachweis über Referenzanwendungen (ODIS/ISTA/Techstream), HIL-Läufe und Golden-Logs. **Bis dahin nicht „J2534-zertifiziert" nennen** — „J2534-kompatibel (in Validierung)" ist die ehrliche Formulierung.
3. **Code-Signing.** EV-Zertifikat, Zeitstempel, Hash im Release-Manifest. Ohne Signatur warnt Windows und viele Werkstatt-PCs blocken die DLL.
4. **64-Bit-Zwang.** Moderne OEM-Anwendungen sind 64-Bit; ein 32-Bit-Build ist nur für Altsoftware nötig.
5. **Haftung.** `SetProgrammingVoltage` bleibt gesperrt, bis die Hardware Pin, Spannung und Strombegrenzung nachweislich beherrscht und ein Test am Prüfstand dokumentiert ist.

---

## 10. Rechtlicher Rahmen

- **Interoperabilität ist zulässig.** Eine API zu implementieren, die eine Norm beschreibt, ist erlaubt; den Normtext zu kopieren oder zu verbreiten nicht (Urheberrecht an der Norm).
- **Keine Herstellerlogos, keine OEM-Optik** in DLL, Installer oder Gerätenamen. Ein Gerätename wie „VAS-kompatibel" ist eine Markenverletzung; „CAN-Interface, J2534-kompatibel" ist es nicht.
- **Kein Umgehen von Security Access.** Die DLL liefert Transport, keine Seed/Key-Logik. Wer das ändert, verliert die Seriosität, mit der die ganze Produktstrategie steht und fällt.
- **Exportkontrolle** unkritisch (keine Verschlüsselung mit Schlüssellänge > 56 Bit in der DLL selbst; TLS im Gateway separat prüfen).

---

## 11. Aufwand bis zur Serienreife

| Stufe | Inhalt | PT |
|---|---|---|
S1 | ABI + Loopback + Tests — **erledigt** | 25 |
S1b | Framing, ISO-TP, Fehlerpfade, simulierte Gegenstelle — **erledigt** | 20 |
S2 | WinUSB: INF, Windows-Build, Geräteerkennung, Test am echten Gerät (Adapter steht) | 20 |
S3 | Registry-Installer (32/64 Bit) + Signierung + VCI-Checker | 20 |
S4 | BLE- und TCP/DoIP-Transport | 35 |
S5 | Validierung gegen 3 Referenzanwendungen + HIL + Golden-Logs | 40 |
S6 | Periodisches Senden, erweiterte Config-Parameter, Feinschliff | 25 |
| **Summe** | | **≈ 185 PT** |

Das entspricht grob 105 T€ Personalvollkosten — passt in den Sach- und Personalblock der V1/V2 im Rechenmodell.

---

## 12. Nächste drei Aufgaben

1. `make` durch MSVC-Build ersetzen und die DLL auf einem Windows-Rechner registrieren; mit ODIS/ISTA als Gerät sichtbar machen.
2. WinUSB-Transport gegen ein echtes Gerät (Tactrix OpenPort 2.0 oder CarDAQ-Plus 3) implementieren und dieselben 26 Tests grün bekommen — das ist der Nachweis, der zählt.
3. Normtext beschaffen und die offenen Konstanten/Tabellen aus §9.1 ergänzen.
