# core — Rust-Kern der App

Der Teil, den Android und iOS teilen. Entscheidung und Abgrenzung zur
J2534-DLL: [ADR-001](../docs/adr-001-kernsprache.md).

**Stand:** Transport-Trait, Loopback und ISO-TP laufen und sind gegen die
C-Implementierung geprüft. UDS, Funktionen und Audit folgen.

```
src/transport.rs   Transport-Trait, CanFrame, Fehler, Capabilities
src/loopback.rs    In-Memory-Transport für Tests
src/isotp.rs       ISO 15765-2: SF/FF/CF/FlowControl, Padding 0xAA
tests/corpus.rs    Konformität gegen den Korpus aus der C-Fassung
tests/corpus/isotp.txt   erzeugt, nicht von Hand gepflegt
```

## Bauen und testen

```bash
cargo test          # 7 Tests: 3 Einheitstests, 4 Korpus-/Regressionstests
cargo build         # ohne Warnungen
```

Keine Abhängigkeiten — der Kern baut offline. Für Android/iOS kommen später
`cargo-ndk` bzw. ein XCFramework dazu (siehe ADR-001 §5).

## Fuzzing

Die beiden Parser haben Ziele in `fuzz/`: ISO-TP (Empfangs- und Sendeweg) und
UDS (Sitzung, Kennung, Fehlerspeicher, NRC-Erkennung).

```bash
sh ../scripts/fuzz.sh        # 300 s je Ziel, die 10 Minuten der Definition of Done
sh ../scripts/fuzz.sh 30     # Rauchprobe nach einer Änderung
```

Braucht `nightly` und `cargo-fuzz`. Der Pre-Push-Haken fährt 20 s je Ziel, wenn
`src/isotp.rs`, `src/uds.rs` oder `fuzz/` geändert wurden. Lauf und Ergebnis des
letzten vollen Durchgangs: [docs/ci.md](../docs/ci.md#fuzzing--lokal-belegt-nicht-in-der-ci).

## Der Konformitätskorpus

`tests/corpus/isotp.txt` wird **nicht von Hand geschrieben**, sondern aus der
C-Implementierung erzeugt:

```bash
make -C ../j2534 corpus
```

`j2534/tools/golden.c` fährt echte UDS-Anfragen durch Framing und ISO-TP gegen
das simulierte Gerät und protokolliert die dabei entstehenden CAN-Rahmen
(Richtung, ID, Länge, Bytes) sowie die zusammengesetzte Antwort. Der Rust-Kern
muss in beide Richtungen dasselbe erzeugen: gleiche Rahmen beim Senden, gleiche
Nutzlast beim Empfangen.

Damit ist die Doppelimplementierung kontrolliert: die DLL und die App können
nicht unbemerkt auseinanderlaufen. Ändert sich das Wire-Format in C, wird der
Korpus neu erzeugt und der Rust-Test schlägt fehl, bis er nachgezogen ist.

### Was der Korpus schon gefunden hat

Der erste Lauf zeigte `flow_out 7E8` — der Tester schickte seinen
Flow-Control-Rahmen auf die **Antwort-ID** des Steuergeräts. In der Simulation
fiel das nie auf, weil das simulierte Gerät Flow-Control ignoriert; auf echter
Hardware bleibt die Übertragung nach dem First Frame stehen. Behoben in
`transport_usb.c` (Flow Control geht auf `0x7E0`), mit Regressionstest
`flow_control_geht_an_das_steuergeraet`.

Im selben Zug wurde die Annahme eines Rahmens auf die Antwort-ID begrenzt:
vorher hätte jedes zweite Steuergerät am Bus die Übertragung stören können.

## Nächste Schritte

1. `uds.rs` — Sitzungen, DTC, Routinen, Tester-Present, NRC-Behandlung (0x78).
2. Weitere Korpus-Szenarien aus dem UDS-Katalog, sobald der C-Client sie fährt.
3. UniFFI-IDL in `core-ffi/`, generierte Bindings nur in der CI.
