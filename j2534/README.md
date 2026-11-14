# j2534 — eigener PassThru-Port

Implementierung der J2534-ABI-Schicht (SAE J2534-1, API 04.04 + 05.00) mit
Transport, ISO-TP und simuliertem Gerät. Spezifikation:
[docs/j2534-dll-spezifikation.md](../docs/j2534-dll-spezifikation.md).

| Baustein | Zustand |
|---|---|
| API-Fläche, Zustandsmaschine, Fehlercodes | fertig, 26 Tests |
| Framing (CRC-16, Resync, Teilstücke) | fertig, Teil der 20 Transport-Tests |
| ISO-TP (SF/FF/CF/FlowControl, beide Richtungen) | fertig |
| Simuliertes Gerät mit UDS-ECU und Fehlerinjektion | fertig |
| **VCI-Checker** (Selbsttest + JSON) | fertig, 34 Tests, CLI `vci-check` |
| **Rollout-Werkzeug** (Kette prüfen, sammeln, auswerten, Wochenverlauf, Diagramm) | fertig, 79 Tests, CLI `rollout` |
| **WinUSB-Adapter** (`src/usb_winusb.c`) | **geschrieben, hier nicht kompiliert** |
| BLE / DoIP, Registry-Installer, INF | offen |

## Bauen und testen (Linux/macOS)

```bash
make test     # baut libj2534.so + vier Testsuiten und führt sie aus (159 Prüfungen)
make corpus   # ISO-TP-Konformitätskorpus für den Rust-Kern erzeugen (core/tests/corpus)
make lib      # nur die Shared Library
make syntax   # nur Syntaxprüfung
make clean
```

Erwartete Ausgabe: vier Blöcke `PASS`, am Ende je `0 Prüfung(en) fehlgeschlagen.`

## VCI-Selbsttest

```bash
./build/vci-check          # gegen das angeschlossene VCI (Windows: WinUSB)
./build/vci-check --sim    # gegen das simulierte Gerät
./build/vci-check --json   # Bericht als JSON für die App
```

Prüft Gerät, Firmware, Bordspannung, Fahrzeugbus, ISO-TP-Mehrfachnachricht und
Leitungsqualität; Rückgabewert 0/1/2. Details: [docs/vci-checker.md](../docs/vci-checker.md).

## Rollout über eine Werkstattkette

```bash
./build/rollout run    --site "Filiale Nord" --device VCI-01 --serial SN-A1
./build/rollout import --plan plan.csv
./build/rollout report --in rollout-results.jsonl --min-pass 80
./build/rollout chart  --in rollout-results.jsonl --out quote.svg --threshold 80
```

`run` prüft vor Ort und hängt das Ergebnis an die Sammeldatei, `import` übernimmt
eingesammelte Berichte laut Plan-CSV, `report` verdichtet je Gerät auf den neuesten
Lauf und zeigt Bestandsquote, Standortzustand und Handlungsbedarf nach Prüfung.
Exit: 0 = durch, 1 = Warnungen, 2 = blockiert. Details: [docs/rollout.md](../docs/rollout.md).

## Windows

```bash
# MSVC
cl /LD /O2 src\core.c src\transport_usb.c src\usb_sim.c src\usb_winusb.c /Iinclude /Isrc /link /DEF:win\j2534.def /OUT:j2534.dll
# MinGW
x86_64-w64-mingw32-gcc -O2 -shared -o j2534.dll src/core.c src/transport_usb.c src/usb_sim.c src/usb_winusb.c -Iinclude -Isrc win/j2534.def
```

Das Gerät braucht eine INF, die es an `winusb.sys` bindet und die
`DeviceInterfaceGUIDs` auf denselben Wert setzt wie
`GUID_DEVINTERFACE_WERKSTATT_VCI` in `src/usb_winusb.c` — sonst findet
`CreateFile` den Pfad nicht. Danach registrieren (Spezifikation §4):

```
HKLM\SOFTWARE\PassThruSupport.04.04\<Vendor> - <Device>
    Vendor          = <Vendor>
    Name            = <Device>
    FunctionLibrary = C:\Pfad\j2534.dll
    ISO15765        = 1
    CAN             = 1
```

## Aufbau

| Datei | Inhalt |
|---|---|
| `include/j2534.h` | Typen, Konstanten, Deklarationen; durchgehend `uint32_t` wegen LP64-ABI |
| `src/core.c` | J2534-API, Zustandsmaschine, Filter, IOCTL — kennt keine Hardware |
| `src/transport_usb.c` | Framing, CRC-16, ISO-TP, Timeouts, Resync, VBATT, Version |
| `src/transport_usb.h` | Transport-API für `core.c` |
| `src/usb_backend.h` | die schmale Hardware-Schnittstelle (open/close/read/write/vbat/firmware) |
| `src/usb_winusb.c` | WinUSB-Adapter: Interface-GUID, Bulk-Pipes, OVERLAPPED-IO mit Abbruch |
| `src/usb_sim.c` | simuliertes Gerät: UDS-ECU, ISO-TP, Fehlerinjektion für Tests |
| `src/checker.c` / `src/checker.h` | Selbsttest: sechs Prüfungen, Schwellwerte, JSON-Vertrag |
| `src/rollout.c` / `src/rollout.h` | Rollout: Parser, Dedupe, Verdichtung, Berichte |
| `tools/vci_check.c` | CLI für den Selbsttest (`--json`, `--sim`) |
| `tools/rollout.c` | CLI für Rollout (`run`, `import`, `report`, `chart`) |
| `tools/golden.c` | erzeugt den ISO-TP-Korpus, gegen den der Rust-Kern geprüft wird |
| `tests/test_session.c` | ABI-Lebenszyklus + Negativfälle (26 Prüfungen) |
| `tests/test_transport.c` | Framing, ISO-TP, Müllbytes, CRC-Fehler, Timeout, Kabelziehen (20) |
| `tests/test_checker.c` | Schwellwerte, Verdicts, JSON-Vertrag (34) |
| `tests/test_rollout.c` | Parser, Dedupe, Auswertung, Politik, Wochenverlauf, Diagramm (79) |
| `win/j2534.def` | Exportliste mit Ordinalen, bindend für `GetProcAddress` |

## Wo echte Hardware eingehängt wird

Genau eine Stelle: `usb_backend` in `src/usb_backend.h`. Ein neuer Transport
(WinUSB, BLE, DoIP) implementiert sechs Funktionen und wird mit
`tp_set_backend()` gesetzt. API-Schicht, ISO-TP, Checker und alle 159 Tests bleiben
unverändert — die Tests laufen dann gegen das echte Gerät statt gegen `usb_sim.c`.

## Was dieser Port nicht ist

Er ist **kein zertifiziertes** J2534-Gerät. Es gibt keinen öffentlichen
Konformitätstest; der Nachweis läuft über Referenzanwendungen (ODIS, ISTA,
Techstream), HIL-Läufe und Golden-Logs. Bis dahin heißt es
„J2534-kompatibel (in Validierung)".

## Verhältnis zum App-Kern

Dieser Port ist **eingefroren**: Bugfixes ja, neue Funktionen nur noch im Rust-Kern
`core/`. Die DLL bedient Windows und fremde Software (ODIS, ISTA) — die App läuft
nicht auf ihr. Begründung, Aufwand und Folgen: [ADR-001](../docs/adr-001-kernsprache.md).
