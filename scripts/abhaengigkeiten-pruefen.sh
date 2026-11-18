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
# Geprüft werden Herkunft, Lizenz und bekannte Sicherheitslücken:
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
# Die Frage nach Lücken beantwortet seit dem 18.11.2026 der dritte Teil: die
# aufgelösten Fassungen und die Plugin-Fassungen aus `android/build.gradle.kts`
# gehen an die offene OSV-Datenbank (api.osv.dev), Treffer werden gegen
# `android/osv-ausnahmen.txt` gehalten. Ohne Konto und ohne Schlüssel — der
# Preis: es gilt nur, was dort gemeldet ist, und der Lauf braucht Netz. Die
# C-Seite hat nichts zu befragen; zehn Kopfddateien aus ISO C und Win32 sind
# keine Bibliothek mit Fassung.
#
# Voraussetzungen: `cc` für die C-Seite; für die JVM-Seite ein JDK
# (`JAVA_HOME` oder `java` im PATH), ein Android SDK (`ANDROID_HOME`,
# `ANDROID_SDK_ROOT` oder `sdk.dir` in `android/local.properties`) und Netz
# beim ersten Lauf (Gradle-Distribution, Modul-Metadaten).
#
# Exit 0 nur, wenn alle drei Teile sauber sind. Fehlt ein Werkzeug (cc, JDK,
# SDK, python3) oder das Netz, endet der Lauf mit 1 und sagt welches —
# „nicht gelaufen“ ist kein Grün.

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

    # Dieselben Zeilen mit Fassung, für die Advisory-Abfrage: OSV will
    # `group:module` und `version` getrennt, nicht als dritten Doppelpunkt-Teil.
    awk '$1 == "ABH:" { print $4 }' "$tmp/abh.txt" |
        sed 's/:\([^:]*\)$/ \1/' | sort -u > "$tmp/fassungen.txt"

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

# --- Bekannte Sicherheitslücken: OSV ---------------------------------------
# Der dritte Teil beantwortet die Frage, die bis zum 18.11.2026 offen blieb.
# Gefragt wird nicht ein Scanner mit eigenem Datenbestand, sondern OSV — offen,
# ohne Konto, mit Maven-Koordinaten und zu jedem Treffer die Fassung, in der er
# behoben ist. Gefragt werden die Fassungen aus dem aufgelösten Graph *und* die
# Plugins aus `android/build.gradle.kts`: das Kotlin-Gradle-Plugin ist genau der
# Ort, an dem die erste gefundene Lücke saß.
#
# Ein Treffer ist rot, es sei denn er steht mit Grund in
# `android/osv-ausnahmen.txt` — dieselbe Bauart wie `deny.toml`. Ein Lauf ohne
# Netz ist kein grüner Lauf: OSV antwortet nicht, also endet er mit 1.
#
osv_pruefen() {
    ausnahmen=android/osv-ausnahmen.txt

    if [ ! -s "$tmp/fassungen.txt" ]; then
        echo
        echo "== Bekannte Sicherheitslücken (OSV)"
        echo "übersprungen: kein aufgelöster Graph (siehe oben)"
        return
    fi

    if [ ! -f "$ausnahmen" ]; then
        rot "$ausnahmen fehlt"
        return
    fi

    # Plugins löst Gradle über einen eigenen Klassenpfad auf; der taucht im
    # Projektgraph nicht auf (gemessen am 18.11.2026: `buildscript.classpath`
    # bleibt bei Plugins aus dem `plugins`-Block leer). Die Fassungen werden
    # deshalb dort gelesen, wo sie entschieden werden, und über eine feste
    # Zuordnung auf Maven-Koordinaten abgebildet. Ein unbekanntes Plugin bricht
    # den Lauf, statt still aus der Abfrage zu fallen.
    awk '/^plugins[[:space:]]*\{/,/^\}/' android/build.gradle.kts > "$tmp/plugins.txt"
    {
        sed -n 's/^[[:space:]]*id("\([^"]*\)")[[:space:]]*version[[:space:]]*"\([^"]*\)".*/\1 \2/p' "$tmp/plugins.txt"
        sed -n 's/^[[:space:]]*kotlin("jvm")[[:space:]]*version[[:space:]]*"\([^"]*\)".*/org.jetbrains.kotlin.jvm \1/p' "$tmp/plugins.txt"
    } > "$tmp/plugin-ids.txt"

    while read -r pid pversion; do
        case "$pid" in
            com.android.application) koord=com.android.tools.build:gradle ;;
            org.jetbrains.kotlin.android | org.jetbrains.kotlin.jvm)
                koord=org.jetbrains.kotlin:kotlin-gradle-plugin ;;
            org.jetbrains.kotlin.plugin.compose)
                koord=org.jetbrains.kotlin:compose-compiler-gradle-plugin ;;
            *)
                rot "Plugin $pid ohne Zuordnung auf ein Maven-Modul — OSV-Abfrage unvollständig"
                continue
                ;;
        esac
        echo "$koord $pversion"
    done < "$tmp/plugin-ids.txt" >> "$tmp/fassungen.txt"

    if ! command -v python3 > /dev/null; then
        rot "kein python3: OSV-Abfrage nicht möglich"
        return
    fi

    echo
    echo "== Bekannte Sicherheitslücken (OSV, Maven)"
    if ! python3 - "$tmp/fassungen.txt" > "$tmp/osv.txt" 2> "$tmp/osv.err" <<'PY'
import json
import sys
import urllib.request

paare = []
for zeile in open(sys.argv[1]):
    felder = zeile.split()
    if len(felder) >= 2:
        paare.append((felder[0], felder[1]))
paare = sorted(set(paare))


def post(url, daten):
    anfrage = urllib.request.Request(
        url, data=json.dumps(daten).encode(),
        headers={'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(anfrage, timeout=60))


fragen = [{'package': {'name': n, 'ecosystem': 'Maven'}, 'version': v}
          for n, v in paare]
antworten = []
for i in range(0, len(fragen), 100):
    antworten.extend(
        post('https://api.osv.dev/v1/querybatch', {'queries': fragen[i:i + 100]})['results'])
if len(antworten) != len(fragen):
    sys.exit('OSV: %d Antworten auf %d Fragen' % (len(antworten), len(fragen)))

print('ABFRAGE %d' % len(paare))

befunde = []
for (name, version), antwort in zip(paare, antworten):
    for eintrag in antwort.get('vulns') or []:
        befunde.append((eintrag['id'], name, version))

if not befunde:
    sys.exit(0)

einzelheiten = {}
for kennung in sorted({b[0] for b in befunde}):
    einzelheiten[kennung] = json.load(
        urllib.request.urlopen('https://api.osv.dev/v1/vulns/' + kennung, timeout=60))

for kennung, name, version in befunde:
    daten = einzelheiten[kennung]
    behoben = sorted({e['fixed']
                      for a in daten.get('affected', [])
                      if a.get('package', {}).get('name') == name
                      for r in a.get('ranges') or []
                      for e in r.get('events') or []
                      if 'fixed' in e})
    stufe = daten.get('database_specific', {}).get('severity') or '-'
    titel = (daten.get('summary') or '').replace('|', '/').strip()
    print('OSV %s %s:%s | %s | %s | behoben in %s'
          % (kennung, name, version, stufe, titel, ', '.join(behoben) or 'keiner Fassung'))
PY
    then
        echo "OSV-Abfrage gescheitert:"
        tail -5 "$tmp/osv.err" | sed 's/^/  /'
        rot "OSV nicht befragbar (Netz oder api.osv.dev)"
        return
    fi

    anzahl=$(sed -n 's/^ABFRAGE \([0-9]*\)$/\1/p' "$tmp/osv.txt")
    echo "$anzahl Fassungen bei api.osv.dev abgefragt"

    treffer=$(awk '$1 == "OSV"' "$tmp/osv.txt")
    if [ -z "$treffer" ]; then
        echo "keine bekannte Lücke zu diesen Fassungen"
    else
        echo "$treffer" | sed 's/^OSV /  /'
    fi

    # Kommentare und Leerzeilen der Ausnahmeliste sind Erklärtext; verglichen
    # wird nur die erste Spalte.
    sed 's/#.*//' "$ausnahmen" | awk 'NF { print $1 }' | sort -u > "$tmp/osv-erlaubt.txt"

    # Leere Datei, wenn nichts gefunden wurde: `comm` braucht beide Seiten.
    awk '$1 == "OSV" { print $2 }' "$tmp/osv.txt" | sort -u > "$tmp/osv-ids.txt"
    erlaubt=$(comm -12 "$tmp/osv-ids.txt" "$tmp/osv-erlaubt.txt")
    offen=$(comm -23 "$tmp/osv-ids.txt" "$tmp/osv-erlaubt.txt")
    if [ -n "$erlaubt" ]; then
        echo "als Ausnahme eingetragen (Grund in $ausnahmen):"
        echo "$erlaubt" | sed 's/^/  /'
    fi
    if [ -n "$offen" ]; then
        rot "bekannte Sicherheitslücke in einem Fremdmodul: $(echo $offen)"
    fi

    nicht_mehr=$(comm -13 "$tmp/osv-ids.txt" "$tmp/osv-erlaubt.txt")
    if [ -n "$nicht_mehr" ]; then
        echo "Hinweis: Ausnahme ohne Treffer — aufräumen:"
        echo "$nicht_mehr" | sed 's/^/  /'
    fi
}

echo "Abhängigkeiten jenseits von Cargo"
echo
c_pruefen
jvm_pruefen
osv_pruefen

echo
if [ "$fehler" -ne 0 ]; then
    echo "Nicht grün: $fehler Befund(e)." >&2
    exit 1
fi
echo "Alle Teile sauber: keine unbekannte Kopfddatei, kein unbekanntes Fremdmodul,"
echo "keine offene Sicherheitslücke."
