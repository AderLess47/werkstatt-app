#!/bin/sh
# Fährt die Fuzz-Ziele des Kerns.
#
# Warum es das gibt: die Definition of Done verlangt „Fuzzing mindestens 10
# Minuten ohne Absturz (Parser-Code)". Ein Lauf, den man von Hand zusammenklickt,
# wird nicht wiederholt — dieser hier steht mit seiner Dauer im Befehl.
#
#     sh scripts/fuzz.sh          # je Ziel 300 s, zusammen die geforderten 10 min
#     sh scripts/fuzz.sh 30       # Rauchprobe nach einer Änderung
#
# Voraussetzungen (einmalig):
#   rustup toolchain install nightly
#   cargo install cargo-fuzz --locked
#
# Ein Absturz beendet den Lauf mit Exit 1 und legt den auslösenden Eingang unter
# core/fuzz/artifacts/isotp/ bzw. .../uds/ ab. Diese Datei ist der Fehlerbericht:
# `cargo +nightly fuzz run <ziel> <datei>` spielt ihn erneut ab.

set -eu

cd "$(dirname "$0")/.."

DAUER=${1:-300}

command -v cargo-fuzz > /dev/null ||
    { echo "FEHLER: cargo-fuzz fehlt — cargo install cargo-fuzz --locked" >&2; exit 1; }
rustc +nightly --version > /dev/null 2>&1 ||
    { echo "FEHLER: nightly fehlt — rustup toolchain install nightly" >&2; exit 1; }

for ziel in isotp uds; do
    echo "== $ziel: ${DAUER}s =="
    # Der Speicherdeckel hält einen Ausreißer davon ab, die Maschine zu füllen;
    # libFuzzer würde sonst bei einer riesigen Zuteilung das OOM-Kill treffen
    # statt eine brauchbare Meldung zu liefern.
    ( cd core && cargo +nightly fuzz run "$ziel" -- \
        -max_total_time="$DAUER" -rss_limit_mb=2048 -print_final_stats=1 )
done

echo "Fertig: zwei Ziele, je ${DAUER}s, ohne Absturz."
