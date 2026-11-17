#!/bin/sh
# Lokaler Ersatz für die CI — dieselben fünf Jobs, die auf einem Runner laufen
# könnten: `c`, `rust`, `clippy`, `fmt`, `jvm-tests`.
#
#     sh scripts/ci-lokal.sh                  # alle fünf
#     sh scripts/ci-lokal.sh fmt clippy       # nur diese beiden
#
# Warum es das gibt: in diesem Repository startet seit 147 Läufen kein Runner
# mehr (`runner_id: 0`, `steps: []`, Abrechnungssperre, siehe docs/ci.md). Ein
# Workflow, der nie läuft, prüft nichts — dieses Skript fährt dieselben Befehle
# auf dieser Maschine und schreibt auf, was dabei herauskam.
#
# Die Job-Namen lassen sich einzeln nennen; der Pre-Push-Hook nutzt das, damit
# ein Push nur die Prüfungen startet, die zu den geänderten Dateien passen.
# Ohne Argumente laufen alle fünf.
#
# Nicht enthalten sind `android` (braucht Android SDK und NDK, hier nicht
# vorhanden) und `bindings` (erzeugt Kotlin- und Swift-Bindings als Artefakt;
# das Erzeugen der Kotlin-Bindings steckt ohnehin im `jvm-tests`-Job, und
# Swift-Bindings brauchen einen macOS-Runner). Beide fehlen bewusst und werden
# in der Übersicht nicht als grün geführt — ein übersprungener Job ist kein
# bestandener.
#
# Unterschied zur CI, damit die Aussage nicht zu groß wird:
#
#   • kein frischer Checkout — geprüft wird der Arbeitsbaum, nicht ein Klon
#   • keine `ubuntu-latest`-Umgebung: Compiler-, JDK- und Gradle-Version sind
#     die dieser Maschine, nicht die der Runner-Images
#   • kein Artefakt-Upload, kein Cache
#   • der Korpus wird byteweise verglichen statt über `git diff`: der
#     Arbeitsbaum kann vom Index abweichen, und ein Vergleich gegen den Index
#     würde dann eine Änderung melden, die es nicht gibt
#
# Reihenfolge wie in `.github/workflows/ci.yml`: erst die billigen Prüfungen,
# dann die teuren. Ein roter Job hält die folgenden **nicht** auf — in der CI
# laufen die Jobs auch unabhängig, und man will alle Befunde auf einmal sehen.
#
# Protokoll: je Job eine Logdatei mit der vollständigen Ausgabe, dazu eine
# Übersicht. Ablage ist `dist/ci-lokal/<Zeitstempel>/` (`dist/` ist ignoriert);
# mit `CI_LOKAL_PROTOKOLL` lässt sich der Pfad vorgeben.
#
# Exit 0 nur, wenn alle gewählten Jobs grün sind. Ein Job, der mangels Werkzeug
# nicht laufen konnte, zählt nicht als grün: er steht als „nicht gelaufen“ in
# der Übersicht und das Skript endet mit 1.
#
# Voraussetzungen: `cargo` mit `rustfmt` und `clippy`, `make`, ein C-Compiler,
# ein JDK (`JAVA_HOME` oder `java` im PATH) für die JVM-Tests.

set -u

cd "$(dirname "$0")/.."

alle_jobs="c rust clippy fmt jvm-tests"

if [ "$#" -eq 0 ]; then
    auswahl=$alle_jobs
else
    auswahl=$*
    for job in $auswahl; do
        case "$job" in
            c | rust | clippy | fmt | jvm-tests) ;;
            *)
                echo "FEHLER: unbekannter Job „$job“ — erlaubt: $alle_jobs" >&2
                exit 2
                ;;
        esac
    done
fi

gewaehlt() { # <job>
    for job in $auswahl; do
        if [ "$job" = "$1" ]; then
            return 0
        fi
    done
    return 1
}

zeitstempel=$(date -u +%Y%m%dT%H%M%SZ)
protokoll=${CI_LOKAL_PROTOKOLL:-dist/ci-lokal/$zeitstempel}
mkdir -p "$protokoll" || { echo "FEHLER: $protokoll lässt sich nicht anlegen" >&2; exit 1; }
uebersicht=$protokoll/uebersicht.txt
: > "$uebersicht"

rot=0
nicht_gelaufen=0

# Ein Job: vollständige Ausgabe ins Log, eine Zeile in die Übersicht.
fahre() { # <job> <funktion>
    job=$1
    funktion=$2
    log=$protokoll/$job.log
    beginn=$(date +%s)
    printf '  %-10s läuft …\n' "$job"

    if "$funktion" > "$log" 2>&1; then
        zustand=grün
    else
        zustand=rot
        rot=$((rot + 1))
        # Das Ende des Logs ist meist die Zeile, die den Grund nennt.
        printf '  %-10s rot — zuletzt:\n' "$job"
        tail -5 "$log" | sed 's/^/               /'
    fi

    dauer=$(( $(date +%s) - beginn ))
    printf '  %-10s %-14s %4s s   %s\n' "$job" "$zustand" "$dauer" "$log"
    printf '%-10s %-14s %4s s\n' "$job" "$zustand" "$dauer" >> "$uebersicht"
}

# Wie `fahre`, aber der Job konnte gar nicht erst starten (fehlendes Werkzeug).
ueberspringe() { # <job> <grund>
    job=$1
    grund=$2
    log=$protokoll/$job.log
    printf '%s\n' "$grund" > "$log"
    printf '  %-10s nicht gelaufen — %s\n' "$job" "$grund"
    printf '%-10s %-14s %4s\n' "$job" "nicht gelaufen" "—" >> "$uebersicht"
    nicht_gelaufen=$((nicht_gelaufen + 1))
}

# --- Job `c`: C-Kern und Konformitätskorpus --------------------------------
job_c() {
    command -v make > /dev/null || { echo "make fehlt"; return 1; }
    command -v cc > /dev/null || { echo "kein C-Compiler (cc) gefunden"; return 1; }

    echo "== make -C j2534 test"
    make -C j2534 test || return 1

    echo
    echo "== Korpus ist aktuell"
    git ls-files --error-unmatch core/tests/corpus/isotp.txt > /dev/null ||
        { echo "core/tests/corpus/isotp.txt ist nicht versioniert"; return 1; }

    sicherung=$(mktemp)
    cp core/tests/corpus/isotp.txt "$sicherung"
    make -C j2534 corpus > /dev/null
    if cmp -s "$sicherung" core/tests/corpus/isotp.txt; then
        echo "unverändert: der C-Kern erzeugt dieselben Bytes"
        cp "$sicherung" core/tests/corpus/isotp.txt
        rm -f "$sicherung"
        return 0
    fi

    # Zurücklegen, nicht löschen: die alte Fassung ist der letzte bekannte
    # Stand, und der Befund soll ohne erneutes Erzeugen nachvollziehbar sein.
    cp "$sicherung" core/tests/corpus/isotp.txt
    rm -f "$sicherung"
    echo "core/tests/corpus/isotp.txt weicht von der Ausgabe des C-Kerns ab"
    return 1
}

# --- Job `rust` ------------------------------------------------------------
job_rust() {
    command -v cargo > /dev/null || { echo "cargo fehlt"; return 1; }

    echo "== core"
    cargo test --locked --manifest-path core/Cargo.toml || return 1

    echo
    echo "== core-ffi"
    cargo test --locked --manifest-path core-ffi/Cargo.toml || return 1
}

# --- Job `clippy` ----------------------------------------------------------
# `--all-targets` schließt die Tests ein; ohne das Flag bleiben genau die
# Warnungen unentdeckt, die den Job sonst nie erreichen (siehe ci.yml).
job_clippy() {
    command -v cargo > /dev/null || { echo "cargo fehlt"; return 1; }

    echo "== core"
    cargo clippy --locked --manifest-path core/Cargo.toml --all-targets -- -D warnings || return 1

    echo
    echo "== core-ffi"
    cargo clippy --locked --manifest-path core-ffi/Cargo.toml --all-targets -- -D warnings || return 1
}

# --- Job `fmt` -------------------------------------------------------------
# Drei Aufrufe, weil die Crates nicht an einer gemeinsamen Workspace-Wurzel
# hängen und `cargo fmt` je Paket arbeitet.
job_fmt() {
    command -v cargo > /dev/null || { echo "cargo fehlt"; return 1; }

    echo "== core"
    cargo fmt --manifest-path core/Cargo.toml --check || return 1

    echo "== core-ffi"
    cargo fmt --manifest-path core-ffi/Cargo.toml --check || return 1

    echo "== core/fuzz"
    cargo fmt --manifest-path core/fuzz/Cargo.toml --check || return 1
}

# --- Job `jvm-tests` -------------------------------------------------------
job_jvm() {
    if [ -z "${JAVA_HOME:-}" ] && ! command -v java > /dev/null; then
        echo "kein JDK: JAVA_HOME ist leer und java liegt nicht im PATH"
        return 1
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
        echo "JAVA_HOME zeigt auf kein JDK: $JAVA_HOME/bin/java fehlt"
        return 1
    fi

    echo "== Host-Bibliothek"
    cargo build --release --locked --manifest-path core-ffi/Cargo.toml || return 1

    echo
    echo "== Kotlin-Bindings erzeugen"
    ./scripts/gen-bindings.sh kotlin || return 1

    echo
    echo "== Alle Tests der App"
    sh scripts/jvm-tests.sh || return 1
}

# --- Ablauf ----------------------------------------------------------------
echo "Lokaler CI-Ersatz — Jobs: $auswahl"
echo "Ausgaben: $protokoll"
echo "Nicht enthalten: android (SDK/NDK fehlt), bindings (Artefakt-Upload)"
echo

if gewaehlt c; then
    fahre c job_c
fi
if gewaehlt rust; then
    fahre rust job_rust
fi
if gewaehlt clippy; then
    fahre clippy job_clippy
fi
if gewaehlt fmt; then
    fahre fmt job_fmt
fi

# Der JVM-Job braucht ein JDK. Fehlt es, ist das kein grüner Lauf: die 174 Tests
# sind dann nicht gefahren worden, und die Übersicht soll das sagen.
if gewaehlt jvm-tests; then
    if [ -z "${JAVA_HOME:-}" ] && ! command -v java > /dev/null; then
        ueberspringe jvm-tests "kein JDK (JAVA_HOME leer, java nicht im PATH)"
    elif [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
        ueberspringe jvm-tests "JAVA_HOME zeigt auf kein JDK: $JAVA_HOME/bin/java fehlt"
    else
        fahre jvm-tests job_jvm
    fi
fi

echo
echo "Übersicht:"
sed 's/^/  /' "$uebersicht"

if [ "$rot" -ne 0 ] || [ "$nicht_gelaufen" -ne 0 ]; then
    echo
    echo "Nicht grün: $rot rot, $nicht_gelaufen nicht gelaufen." >&2
    echo "Logs: $protokoll" >&2
    exit 1
fi

echo
echo "Alle gewählten Jobs grün."
