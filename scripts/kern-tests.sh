#!/bin/sh
# Rust- und C-Kern prüfen — dasselbe, was die CI-Jobs `c` und `rust` fahren.
#
# Der Pre-Push-Hook ruft dieses Skript auf; einzeln starten geht genauso:
#
#     sh scripts/kern-tests.sh
#
# Vier Schritte, in dieser Reihenfolge (billig vor teuer):
#
#   1. C-Kern: ABI-, Transport- und Checker-Tests gegen das Simulationsgerät
#      (`make -C j2534 test`, 165 Prüfungen)
#   2. Korpus: ist `core/tests/corpus/isotp.txt` noch das, was der C-Kern
#      erzeugt? Der Korpus ist der Vertrag zwischen C-Kern und Rust-Kern — ein
#      veralteter Korpus lässt den Rust-Test grün aussehen, obwohl sich das
#      Drahtformat verschoben hat.
#   3. Rust-Kern (`core`)
#   4. FFI-Schicht (`core-ffi`)
#
# Der Korpus wird zur Prüfung neu erzeugt und danach **zurückgelegt**, nicht
# gelöscht oder auf HEAD zurückgesetzt: ein veralteter Korpus soll gemeldet
# werden, und eine unversionierte Änderung im Arbeitsbaum soll niemandem
# verloren gehen.

set -eu

cd "$(dirname "$0")/.."

fehler() {
    echo "FEHLER: $*" >&2
    exit 1
}

echo "1/4  C-Kern (ABI, Transport, Checker gegen das Simulationsgerät)"
command -v make > /dev/null || fehler "make fehlt"
command -v cc > /dev/null || fehler "kein C-Compiler (cc) gefunden"
make -C j2534 test

echo "2/4  Korpus ist aktuell"
sicherung=$(mktemp)
trap 'rm -f "$sicherung"' EXIT INT TERM
git ls-files --error-unmatch core/tests/corpus/isotp.txt > /dev/null ||
    fehler "core/tests/corpus/isotp.txt ist nicht versioniert"
cp core/tests/corpus/isotp.txt "$sicherung"
make -C j2534 corpus > /dev/null
if ! git diff --quiet -- core/tests/corpus/isotp.txt; then
    cp "$sicherung" core/tests/corpus/isotp.txt
    fehler "core/tests/corpus/isotp.txt ist veraltet — neu erzeugen mit: make -C j2534 corpus"
fi
cp "$sicherung" core/tests/corpus/isotp.txt

echo "3/4  Rust-Kern"
command -v cargo > /dev/null || fehler "cargo fehlt — der Rust-Kern ist so nicht prüfbar"
cargo test --locked --manifest-path core/Cargo.toml

echo "4/4  FFI-Schicht"
cargo test --locked --manifest-path core-ffi/Cargo.toml

echo "alle vier Schritte grün"
