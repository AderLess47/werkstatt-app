#!/bin/sh
# Prüft, ob die Testzahlen in den Jobnamen von `.github/workflows/ci.yml` noch
# stimmen.
#
# Warum es das gibt: `name: JVM-Tests ohne Gerät (157)` ist eine Behauptung. Sie
# stimmt genau bis zum nächsten neuen Test — danach sagt der Jobname etwas
# Falsches, und niemand merkt es, weil keine Prüfung ihn liest. Genau das ist
# passiert, als der SFD-Ausbau zwölf JVM-Tests dazubrachte und die Namen
# weiterhin 125 sagten.
#
#     sh scripts/zahlen-pruefen.sh
#
# Gezählt wird, was die Suiten **melden**, nicht was im Quelltext deklariert
# ist: ein `#[test]` in einem Modul, das nicht kompiliert, oder ein `@Test` in
# einer Klasse, die `jvm-tests.sh` ausschließt (Compose, USB), wäre sonst
# mitgezählt und die Zahl würde wieder lügen.
#
# Ist-Zahlen kommen aus den Läufen, Soll-Zahlen aus dem Workflow:
#
#   • C           — Zeilen `PASS …` aus `make -C j2534 test`, gegen den
#                   Schrittnamen `Tests (N Prüfungen)`
#   • Rust-Kern   — `test result: … N passed` der `unittests src/lib.rs`
#   • Korpus      — dasselbe für `tests/*.rs` aus `core`
#   • FFI         — dasselbe für `core-ffi`
#   • JVM         — `OK (N tests)` aus der JUnit-Ausgabe
#
# Exit 1, sobald eine Zahl abweicht. Dauer: wie die Suiten selbst, etwa zwei
# Minuten — deshalb steht das Skript nicht im Pre-Push-Hook.
#
# Pfade über Umgebungsvariablen:
#   JAVA_HOME    JDK für die JVM-Tests (sonst `java` aus dem PATH)
#   VCI_FFI_LIB  Verzeichnis mit libvci_ffi.so (Vorgabe: core-ffi/target/release)

set -eu

cd "$(dirname "$0")/.."

WORKFLOW=.github/workflows/ci.yml
[ -f "$WORKFLOW" ] || { echo "FEHLER: $WORKFLOW fehlt" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

fehler() {
    echo "FEHLER: $*" >&2
    exit 1
}

# Bricht mit dem Ende des Protokolls ab — die letzte Zeile nennt meist den Grund
# (fehlender Linker, falsches ABI, Übersetzungsfehler).
abgebrochen() { # <logdatei> <was>
    tail -20 "$1" >&2
    fehler "$2 ist nicht durchgelaufen"
}

# --- 1. Host-Bibliothek -----------------------------------------------------
# Die JVM-Tests laden den Host-Build, nicht die .so aus jniLibs (die ist gegen
# Bionic gelinkt). Ohne diesen Schritt prüften sie gegen einen alten Stand.
echo "1/5  Host-Bibliothek bauen"
command -v cargo > /dev/null || fehler "cargo fehlt — der Rust-Kern ist so nicht prüfbar"
cargo build --release --locked --manifest-path core-ffi/Cargo.toml > "$TMP/host.log" 2>&1 ||
    abgebrochen "$TMP/host.log" "der Host-Build"

# --- 2. C-Kern --------------------------------------------------------------
# `make -C j2534 test` meldet jede Prüfung als eigene Zeile `PASS  <was>`; die
# Prüfungen des VCI-Checkers sind darunter (eine heißt selbst „6 Prüfungen“).
# Deshalb wird gezählt, nicht die Zeile mit der Zahl geglaubt.
echo "2/5  C-Kern zählen (make -C j2534 test)"
command -v make > /dev/null || fehler "make fehlt"
command -v cc > /dev/null || fehler "kein C-Compiler (cc) gefunden"
make -C j2534 test > "$TMP/c.log" 2>&1 || abgebrochen "$TMP/c.log" "make -C j2534 test"
C_IST=$(grep -c '^PASS' "$TMP/c.log" || true)
[ "$C_IST" -gt 0 ] || fehler "im C-Protokoll steht keine PASS-Zeile"

# --- 3./4. Rust -------------------------------------------------------------
# Ein `cargo test` je Crate, und die Ergebnisse nach Ziel getrennt: die
# Unit-Tests stehen in `unittests src/lib.rs`, der Konformitätskorpus in
# `tests/*.rs`, Doc-Tests zählen nicht mit (sie sind 0 und keine Prüfung des
# Drahtformats).
zaehle_cargo() { # <manifest> <logdatei> → "<unit> <korpus>"
    cargo test --locked --manifest-path "$1" > "$2" 2>&1 ||
        abgebrochen "$2" "cargo test für $1"
    awk '
        /^ +Running /  { ziel = $2 " " $3; next }
        /^ +Doc-tests/ { ziel = "doc"; next }
        /^test result: / {
            for (i = 1; i <= NF; i++) {
                if ($i == "passed;") { n = $(i - 1); break }
            }
            if (ziel ~ /^unittests/) einheit += n
            else if (ziel ~ /^tests\//) korpus += n
        }
        END { printf "%d %d\n", einheit, korpus }
    ' "$2"
}

echo "3/5  Rust-Kern zählen (cargo test core)"
set -- $(zaehle_cargo core/Cargo.toml "$TMP/core.log")
KERN_IST=${1:-0}
KORPUS_IST=${2:-0}

echo "4/5  FFI-Schicht zählen (cargo test core-ffi)"
set -- $(zaehle_cargo core-ffi/Cargo.toml "$TMP/ffi.log")
FFI_IST=${1:-0}
FFI_KORPUS=${2:-0}
[ "$FFI_KORPUS" = 0 ] ||
    fehler "die FFI-Schicht meldet $FFI_KORPUS Korpus-Tests — die Zählung trennt nicht richtig"

# --- 5. JVM -----------------------------------------------------------------
echo "5/5  JVM-Tests zählen (sh scripts/jvm-tests.sh)"
if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
    fehler "JAVA_HOME zeigt auf kein JDK: $JAVA_HOME/bin/java fehlt"
fi
sh scripts/jvm-tests.sh > "$TMP/jvm.log" 2>&1 || abgebrochen "$TMP/jvm.log" "scripts/jvm-tests.sh"
JVM_IST=$(sed -n 's/^OK (\([0-9][0-9]*\) tests)$/\1/p' "$TMP/jvm.log" | head -1)
[ -n "$JVM_IST" ] || {
    tail -20 "$TMP/jvm.log" >&2
    fehler "in der JUnit-Ausgabe steht keine Zeile „OK (N tests)“"
}

# --- Soll-Zahlen aus dem Workflow ------------------------------------------
# Gelesen wird genau das, was im Jobnamen steht — nicht eine Kopie in diesem
# Skript, die selbst veralten würde.
RUST_ZEILE=$(grep -m1 'name: Rust-Kern (' "$WORKFLOW" || true)
C_ZEILE=$(grep -m1 'name: Tests (' "$WORKFLOW" || true)
JVM_ZEILE=$(grep -m1 'name: JVM-Tests ohne Gerät (' "$WORKFLOW" || true)

[ -n "$RUST_ZEILE" ] || fehler "kein Jobname „Rust-Kern (…)“ in $WORKFLOW"
[ -n "$C_ZEILE" ] || fehler "kein Schrittname „Tests (… Prüfungen)“ in $WORKFLOW"
[ -n "$JVM_ZEILE" ] || fehler "kein Jobname „JVM-Tests ohne Gerät (…)“ in $WORKFLOW"

n_te_zahl() { # <zeile> <n>
    printf '%s\n' "$1" | grep -o '[0-9][0-9]*' | sed -n "$2p"
}

RUST_SOLL_KERN=$(n_te_zahl "$RUST_ZEILE" 1)
RUST_SOLL_KORPUS=$(n_te_zahl "$RUST_ZEILE" 2)
FFI_SOLL=$(n_te_zahl "$RUST_ZEILE" 3)
C_SOLL=$(n_te_zahl "$C_ZEILE" 1)
JVM_SOLL=$(n_te_zahl "$JVM_ZEILE" 1)

# --- Vergleich --------------------------------------------------------------
ABWEICHUNG=0
pruefe() { # <bezeichnung> <ist> <soll>
    if [ -z "${3:-}" ]; then
        printf '  %-14s %6s %8s   keine Zahl im Namen\n' "$1" "$2" "—"
        ABWEICHUNG=1
    elif [ "$2" = "$3" ]; then
        printf '  %-14s %6s %8s   gleich\n' "$1" "$2" "$3"
    else
        printf '  %-14s %6s %8s   ABWEICHUNG\n' "$1" "$2" "$3"
        ABWEICHUNG=1
    fi
}

echo
echo "Lokal gezählt gegen die Namen in $WORKFLOW:"
printf '  %-14s %6s %8s   %s\n' "Testart" "lokal" "Soll" "Ergebnis"
pruefe "C" "$C_IST" "$C_SOLL"
pruefe "Rust-Kern" "$KERN_IST" "$RUST_SOLL_KERN"
pruefe "Korpus" "$KORPUS_IST" "$RUST_SOLL_KORPUS"
pruefe "FFI" "$FFI_IST" "$FFI_SOLL"
pruefe "JVM" "$JVM_IST" "$JVM_SOLL"
echo

if [ "$ABWEICHUNG" -ne 0 ]; then
    echo "Die Jobnamen passen nicht zu den lokal gezählten Tests." >&2
    echo "Nachziehen: die Zahlen in $WORKFLOW und die Tabelle in docs/ci.md." >&2
    echo "Wichtig: die CI läuft derzeit nicht — die lokale Zahl ist der Beleg," >&2
    echo "der Jobname nur die Ankündigung." >&2
    exit 1
fi

echo "Alle Zahlen stimmen: lokal gezählt und in den Jobnamen genannt."
