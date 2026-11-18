#!/bin/sh
# Abhängigkeiten jenseits von Cargo: die Kotlin-/Java-Seite der App und die
# C-Seite (`j2534/`).
#
#     sh scripts/abhaengigkeiten-pruefen.sh
#
# Warum es das gibt: `deny` (cargo-deny) sieht nur die Rust-Pakete. Alles, was
# die Android-Module aus Google, Maven Central und JitPack ziehen, und jede
# fremde Kopfddatei, die der C-Kern einbindet, blieb bis zum 18.11.2026
# ungeprüft — in docs/ci.md stand das als offene Lücke. Ein Job, der sie nur
# benennt, schließt sie nicht.
#
# Geprüft wird die **Herkunft**, nicht die Sicherheit:
#
#   • JVM: der *aufgelöste* Graph der beiden Android-Module (nicht die
#     Erklärungen in den Build-Dateien — die sagen nichts über transitive
#     Module) wird gegen `android/abhaengigkeiten.txt` gehalten. Jedes
#     Fremdmodul braucht dort eine Zeile mit seiner Lizenz; ein neues Modul
#     bricht den Lauf, bis jemand die Lizenz nachschlägt und einträgt. Dieselbe
#     Bauart wie `deny.toml`: die Entscheidung ist menschlich, die Prüfung
#     mechanisch.
#   • C: `j2534/` bindet nur ISO-C- und Win32-Kopfddateien ein; die Liste steht
#     in `j2534/abhaengigkeiten.txt`. Zusätzlich prüft `cc -M` (der Compiler,
#     nicht ein Textvergleich), dass keine Abhängigkeit aus einem Pfad außerhalb
#     des Projekts und außerhalb der Systemverzeichnisse kommt — damit fällt
#     auch ein per `-I` hereingezogener Fremdpfad auf.
#
# Was der Lauf **nicht** prüft: Sicherheitslücken (CVEs). Für die JVM-Seite
# bräuchte es eine eigene Advisory-Datenbank (NVD/OSV) samt Zugang und
# Aktualisierung, für die C-Seite gibt es ohne Fremdbibliothek nichts zu
# scannen. Die Lücke bleibt also bestehen und steht so in docs/ci.md; dieser
# Lauf behauptet nicht, sie geschlossen zu haben.
#
# Voraussetzungen: `cc` für die C-Seite; für die JVM-Seite ein JDK
# (`JAVA_HOME` oder `java` im PATH), ein Android SDK (`ANDROID_HOME`,
# `ANDROID_SDK_ROOT` oder `sdk.dir` in `android/local.properties`) und Netz
# beim ersten Lauf (Gradle-Distribution, Modul-Metadaten).
#
# Exit 0 nur, wenn beide Seiten sauber sind. Fehlt ein Werkzeug, endet der Lauf
# mit 1 und sagt welches — „nicht gelaufen“ ist kein Grün.

set -u

cd "$(dirname "$0")/.." || exit 1

tmp=$(mktemp -d) || { echo "FEHLER: kein temporäres Verzeichnis" >&2; exit 1; }
trap 'rm -rf "$tmp"' EXIT INT TERM

fehler=0
rot() {
    echo "ROT: $*"
    fehler=$((fehler + 1))
}

# --- C-Seite: j2534/ -------------------------------------------------------
# Zwei Prüfungen, die einander ergänzen: die Liste der eckigen Includes (was
# der Quelltext *nennt*, auch unter `#ifdef _WIN32`) und der Include-Graph des
# Compilers (was tatsächlich aufgelöst wird, inklusive Pfaden).
c_pruefen() {
    liste=j2534/abhaengigkeiten.txt
    if [ ! -f "$liste" ]; then
        rot "$liste fehlt"
        return
    fi

    echo "== Kopfddateien des C-Kerns (j2534/)"

    grep -ho '#include <[^>]*>' \
        j2534/src/*.c j2534/src/*.h j2534/include/*.h \
        j2534/tests/*.c j2534/tools/*.c |
        sed 's/^#include <//; s/>$//' | sort -u > "$tmp/c-gefunden.txt"

    sed 's/#.*//' "$liste" | awk 'NF { print $1 }' | sort -u > "$tmp/c-erlaubt.txt"

    anzahl=$(wc -l < "$tmp/c-gefunden.txt" | tr -d ' ')
    unbekannt=$(comm -23 "$tmp/c-gefunden.txt" "$tmp/c-erlaubt.txt")
    if [ -n "$unbekannt" ]; then
        echo "Diese Kopfddateien stehen nicht in $liste:"
        echo "$unbekannt" | sed 's/^/  /'
        rot "unbekannte Kopfddatei in j2534/ — Herkunft klären und eintragen"
    else
        echo "$anzahl Kopfddateien, alle in $liste"
    fi

    # Anders als bei `deny` ist ein Eintrag, der nichts mehr trifft, nur ein
    # Hinweis: `windows.h` und Verwandte sind unter Linux nie aufgelöst, stehen
    # aber zu Recht in der Liste. Ein Rot wäre hier ein Fehlalarm.
    nicht_mehr=$(comm -13 "$tmp/c-gefunden.txt" "$tmp/c-erlaubt.txt")
    if [ -n "$nicht_mehr" ]; then
        echo "Hinweis: genannt, aber nicht mehr eingebunden:"
        echo "$nicht_mehr" | sed 's/^/  /'
    fi

    if ! command -v cc > /dev/null; then
        rot "kein C-Compiler (cc): Include-Graph nicht prüfbar"
        return
    fi

    if ! (cd j2534 && cc -M -Iinclude src/*.c) > "$tmp/cc.txt" 2> "$tmp/cc.err"; then
        echo "cc -M ist gescheitert:"
        tail -5 "$tmp/cc.err" | sed 's/^/  /'
        rot "Include-Graph des C-Kerns nicht lesbar"
        return
    fi

    # `src/` und `include/` sind das Projekt, alles Absolute ist System oder
    # Compiler. Was übrig bleibt, kommt von außerhalb — genau das soll auffallen.
    fremd=$(tr ' ' '\n' < "$tmp/cc.txt" |
        sed 's/\\$//' |
        awk 'NF && $0 !~ /:$/ && $0 !~ /^src\// && $0 !~ /^include\// && $0 !~ /^\//' |
        sort -u)
    if [ -n "$fremd" ]; then
        echo "Diese Abhängigkeiten liegen außerhalb von j2534/ und der Systempfade:"
        echo "$fremd" | sed 's/^/  /'
        rot "Include-Pfad von außerhalb des Projekts"
    else
        echo "Include-Graph: nur j2534/ und Systempfade"
    fi
}

# --- Kotlin-/Java-Seite: Gradle-Graph --------------------------------------
# Das Init-Skript hängt eine Aufgabe an die beiden Module, ohne die
# Build-Dateien anzufassen: der Graph wird abgefragt, nicht der Build geändert.
# Ausgegeben wird je Modul und Konfiguration `<group>:<module>:<version>`; die
# Version bleibt im Log (sie ist der Stand von heute), die Liste führt nur das
# Modul (sie sagt, *was* fremd ist).
jvm_pruefen() {
    liste=android/abhaengigkeiten.txt
    if [ ! -f "$liste" ]; then
        rot "$liste fehlt"
        return
    fi

    if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] &&
        ! grep -qs '^sdk\.dir=' android/local.properties; then
        rot "kein Android SDK: ANDROID_HOME/ANDROID_SDK_ROOT leer, android/local.properties ohne sdk.dir"
        return
    fi

    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_kurz=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
    elif command -v java > /dev/null; then
        java_kurz=$(java -version 2>&1 | head -1)
    else
        rot "kein JDK: JAVA_HOME ist leer und java liegt nicht im PATH"
        return
    fi

    echo
    echo "== Kotlin-/Java-Abhängigkeiten (Gradle, $java_kurz)"

    cat > "$tmp/abh.gradle" <<'GRADLE'
gradle.projectsEvaluated {
    gradle.rootProject.allprojects { p ->
        def namen = []
        if (p.name == 'bindings') namen = ['runtimeClasspath', 'testRuntimeClasspath']
        if (p.name == 'app') namen = ['releaseRuntimeClasspath', 'debugUnitTestRuntimeClasspath']
        if (namen.isEmpty()) return null
        p.tasks.register('abhaengigkeitenListe') {
            doLast {
                namen.each { n ->
                    def c = p.configurations.findByName(n)
                    if (c == null || !c.canBeResolved) {
                        println "ABH-ROT ${p.path} ${n} nicht auflösbar"
                        return
                    }
                    c.incoming.resolutionResult.allComponents.each { comp ->
                        def mv = comp.moduleVersion
                        // Eigene Module (Projektabhängigkeiten) sind kein Fremdcode.
                        if (mv != null && !(comp.id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier)) {
                            println "ABH: ${p.name} ${n} ${mv.group}:${mv.name}:${mv.version}"
                        }
                    }
                }
            }
        }
    }
}
GRADLE

    if ! sh android/gradlew -p android --init-script "$tmp/abh.gradle" -q --console=plain \
        :app:abhaengigkeitenListe :bindings:abhaengigkeitenListe \
        > "$tmp/abh.txt" 2> "$tmp/abh.err"; then
        echo "Gradle-Lauf gescheitert — Ende der Ausgabe:"
        tail -20 "$tmp/abh.err" | sed 's/^/  /'
        rot "Gradle-Graph nicht aufgelöst"
        return
    fi

    nicht_aufloesbar=$(grep '^ABH-ROT ' "$tmp/abh.txt" || true)
    if [ -n "$nicht_aufloesbar" ]; then
        echo "$nicht_aufloesbar" | sed 's/^/  /'
        rot "Konfiguration nicht auflösbar"
        return
    fi

    # ABH: <projekt> <konfiguration> <group>:<module>:<version>
    awk '$1 == "ABH:" { print $4 }' "$tmp/abh.txt" |
        sed 's/:[^:]*$//' | sort -u > "$tmp/jvm-gefunden.txt"

    sed 's/#.*//' "$liste" |
        awk 'NF >= 2 {
                 mod = $1
                 zeile = $0
                 sub(/^[[:space:]]*[^[:space:]]+[[:space:]]+/, "", zeile)
                 sub(/[[:space:]]+$/, "", zeile)
                 print mod "\t" zeile
             }' > "$tmp/jvm-erlaubt.txt"
    cut -f1 "$tmp/jvm-erlaubt.txt" | sort -u > "$tmp/jvm-erlaubt-module.txt"

    anzahl=$(wc -l < "$tmp/jvm-gefunden.txt" | tr -d ' ')
    echo "$anzahl Fremdmodule im Graph, aufgelöste Fassungen:"
    awk '$1 == "ABH:" { print "  " $4 }' "$tmp/abh.txt" | sort -u

    unbekannt=$(comm -23 "$tmp/jvm-gefunden.txt" "$tmp/jvm-erlaubt-module.txt")
    if [ -n "$unbekannt" ]; then
        echo
        echo "Diese Module stehen nicht in $liste:"
        echo "$unbekannt" | sed 's/^/  /'
        rot "unbekanntes Fremdmodul — Lizenz nachschlagen und in $liste eintragen"
    fi

    nicht_mehr=$(comm -13 "$tmp/jvm-gefunden.txt" "$tmp/jvm-erlaubt-module.txt")
    if [ -n "$nicht_mehr" ]; then
        echo
        echo "Hinweis: in $liste genannt, aber nicht mehr im Graph:"
        echo "$nicht_mehr" | sed 's/^/  /'
    fi

    if [ -z "$unbekannt" ]; then
        echo
        echo "Lizenzen im Graph (aus $liste):"
        awk -F'\t' 'NR == FNR { liz[$1] = $2; next }
                    ($1 in liz) { anzahl[liz[$1]]++ }
                    END { for (l in anzahl) print "  " anzahl[l] "\t" l }' \
            "$tmp/jvm-erlaubt.txt" "$tmp/jvm-gefunden.txt" |
            sort -rn
    fi
}

echo "Abhängigkeiten jenseits von Cargo"
echo
c_pruefen
jvm_pruefen

echo
if [ "$fehler" -ne 0 ]; then
    echo "Nicht grün: $fehler Befund(e)." >&2
    exit 1
fi
echo "Beide Seiten sauber: keine unbekannte Kopfddatei, kein unbekanntes Fremdmodul."
