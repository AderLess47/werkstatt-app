#!/bin/sh
# Lokaler Ersatz für die CI — dieselben sieben Jobs, die auf einem Runner
# laufen könnten: `c`, `rust`, `clippy`, `fmt`, `deny`, `deps`, `jvm-tests`.
#
#     sh scripts/ci-lokal.sh                  # alle sieben
#     sh scripts/ci-lokal.sh fmt clippy       # nur diese beiden
#
# Warum es das gibt: in diesem Repository startet seit 147 Läufen kein Runner
# mehr (`runner_id: 0`, `steps: []`, Abrechnungssperre, siehe docs/ci.md). Ein
# Workflow, der nie läuft, prüft nichts — dieses Skript fährt dieselben Befehle
# auf dieser Maschine und schreibt auf, was dabei herauskam.
#
# Die Job-Namen lassen sich einzeln nennen; der Pre-Push-Hook nutzt das, damit
# ein Push nur die Prüfungen startet, die zu den geänderten Dateien passen.
# Ohne Argumente laufen alle sieben.
#
# Nicht enthalten sind `android` (baut die NDK-Bibliotheken für beide ABIs und
# die APK — dafür braucht es NDK, `cargo-ndk` und Minuten; `deps` fragt nur den
# Gradle-Graphen ab und braucht deshalb bloß JDK und SDK) und `bindings` (erzeugt
# Kotlin- und Swift-Bindings als Artefakt; das Erzeugen der Kotlin-Bindings
# steckt ohnehin im `jvm-tests`-Job, und Swift-Bindings brauchen einen
# macOS-Runner). Beide fehlen bewusst und werden in der Übersicht nicht als grün
# geführt — ein übersprungener Job ist kein bestandener.
#
# Unterschied zur CI, damit die Aussage nicht zu groß wird:
#
#   • kein frischer Checkout — geprüft wird der Arbeitsbaum, nicht ein Klon
#   • keine `ubuntu-latest`-Umgebung: Compiler-, JDK-, Gradle- und
#     cargo-deny-Version sind die dieser Maschine, nicht die der Runner-Images
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
# ein JDK (`JAVA_HOME` oder `java` im PATH) für die JVM-Tests, `cargo-deny` für
# den `deny`-Job. cargo-deny liegt nicht bei cargo bei; fehlt es, steht der Job
# als „nicht gelaufen“ in der Übersicht und das Skript endet mit 1.
#
# `deps` braucht zusätzlich ein Android SDK (`ANDROID_HOME`,
# `ANDROID_SDK_ROOT` oder `sdk.dir` in `android/local.properties`): der Job löst
# den Gradle-Graphen der App auf, und `com.android.application` findet das SDK
# schon beim Konfigurieren. Gebaut wird dort nichts — kein NDK, kein Gradle-Build.

set -u

cd "$(dirname "$0")/.."

alle_jobs="c rust clippy fmt deny deps jvm-tests"

if [ "$#" -eq 0 ]; then
    auswahl=$alle_jobs
else
    auswahl=$*
    for job in $auswahl; do
        case "$job" in
            c | rust | clippy | fmt | deny | deps | jvm-tests) ;;
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

# --- Job `deny` ------------------------------------------------------------
# Drei Läufe für drei Manifeste, wie in `ci.yml`: `core`, `core-ffi` und
# `core/fuzz` hängen nicht an einer gemeinsamen Workspace-Wurzel, und cargo-deny
# liest den Graph aus dem Lockfile, das zu seinem Manifest gehört. Ein einzelner
# Lauf über alle würde einen der Stände verschweigen, und im Log stünde nicht,
# welcher Teil gemeint war.
#
# `--locked` wie in der CI: ohne das Flag löst cargo die Abhängigkeiten bei
# Bedarf neu auf, und dann prüft der lokale Lauf einen anderen Graph als der
# Runner. Genau daran ist Lauf #333 gescheitert — `core/fuzz/Cargo.lock` lag
# nicht im Repository, und `--locked` verbietet das Auflösen (siehe docs/ci.md).
#
# Die Fassung ist die dieser Maschine, nicht die des Aktions-Images (0.21.0);
# deshalb steht sie im Log. Der erste Lauf holt die RustSec-Datenbank in den
# Cache des Benutzers: ohne Netz und ohne Cache endet er rot, und das ist dann
# kein Lizenzbefund.
job_deny() {
    fassung=$(cargo-deny --version 2>/dev/null | sed 's/^[^ ]* //')
    echo "== cargo-deny ${fassung:-unbekannt} (Aktions-Image der CI: 0.21.0)"

    for paket in core core-ffi core/fuzz; do
        echo
        echo "== $paket/Cargo.toml"
        cargo-deny --log-level warn --manifest-path "$paket/Cargo.toml" --locked \
            check licenses advisories || return 1
    done
}

# --- Job `deps` ------------------------------------------------------------
# Kotlin-/Java-Abhängigkeiten und die C-Seite — genau das, was `deny` nicht
# sieht. Die Einzelheiten (Erlaubnislisten, Gradle-Init-Skript, Prüfung des
# Include-Graphen) stehen im Skript; hier steht nur der Aufruf, damit die
# Ausgabe im Log dieses Laufs landet. Der erste Lauf holt Gradle-Distribution
# und Modul-Metadaten aus dem Netz; warm sind es wenige Sekunden.
job_deps() {
    sh scripts/abhaengigkeiten-pruefen.sh || return 1
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
echo "Nicht enthalten: android (NDK-Bau und APK), bindings (Artefakt-Upload)"
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

# `deny` braucht ein eigenes Werkzeug. Fehlt es, ist der Job nicht gefahren —
# und das ist etwas anderes als grün, also steht es so in der Übersicht.
if gewaehlt deny; then
    if command -v cargo-deny > /dev/null; then
        fahre deny job_deny
    else
        ueberspringe deny "cargo-deny fehlt: cargo install cargo-deny --locked"
    fi
fi

# JDK und Android SDK entscheiden, ob `deps` und `jvm-tests` überhaupt starten
# können. Fehlt eines, ist das kein grüner Lauf: die Prüfung ist dann nicht
# gefahren worden, und die Übersicht soll das sagen statt zu schweigen.
jdk_grund=""
if [ -z "${JAVA_HOME:-}" ] && ! command -v java > /dev/null; then
    jdk_grund="kein JDK (JAVA_HOME leer, java nicht im PATH)"
elif [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
    jdk_grund="JAVA_HOME zeigt auf kein JDK: $JAVA_HOME/bin/java fehlt"
fi

sdk_grund=""
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] &&
    ! grep -qs '^sdk\.dir=' android/local.properties; then
    sdk_grund="kein Android SDK (ANDROID_HOME/ANDROID_SDK_ROOT leer, local.properties ohne sdk.dir)"
fi

# Seit dem 18.11.2026 fragt `deps` zusätzlich OSV. Dafür braucht es `python3`;
# ohne das Werkzeug ist die Advisory-Antwort nicht geholt worden, und das gehört
# in die Übersicht statt in einen roten Befund. Das Netz lässt sich hier nicht
# prüfen, ohne selbst zu warten — fehlt es, wird der Job rot und nennt OSV.
py_grund=""
if ! command -v python3 > /dev/null; then
    py_grund="kein python3 (OSV-Abfrage nicht möglich)"
fi

if gewaehlt deps; then
    if [ -n "$jdk_grund" ]; then
        ueberspringe deps "$jdk_grund"
    elif [ -n "$sdk_grund" ]; then
        ueberspringe deps "$sdk_grund"
    elif [ -n "$py_grund" ]; then
        ueberspringe deps "$py_grund"
    else
        fahre deps job_deps
    fi
fi

if gewaehlt jvm-tests; then
    if [ -n "$jdk_grund" ]; then
        ueberspringe jvm-tests "$jdk_grund"
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
