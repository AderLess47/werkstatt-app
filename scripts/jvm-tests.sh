#!/bin/sh
# Führt die JVM-Tests von :app ohne Gradle, ohne Android SDK und ohne Gerät aus.
#
# Warum es das gibt: `:app:testDebugUnitTest` braucht das Android SDK und (über
# Gradle) Netzzugang beim ersten Lauf. Die Tests selbst sind gewöhnliche
# JUnit-4-Tests und brauchen nur drei Dinge:
#
#   1. einen Kotlin-Compiler (aus dem Gradle-Cache, sonst von Maven Central),
#   2. ein JDK 17 oder neuer,
#   3. die native Bibliothek — und zwar eine für **diesen** Rechner.
#
# Punkt 3 ist die Falle: die Bibliothek in `android/app/src/main/jniLibs` ist für
# Bionic gebaut (Android). Sie nennt `libdl.so`/`libc.so` und verlangt Symbole der
# Version `LIBC` — auf glibc heißt die Version `GLIBC_2.2.5`, und `libdl` ist
# heute ein leerer Platzhalter. `dlopen` scheitert dort also zwangsläufig; das ist
# kein Fehler der Tests. Gebraucht wird der Host-Build:
#
#     cargo build --release --manifest-path core-ffi/Cargo.toml
#     # → core-ffi/target/release/libvci_ffi.so  (glibc, ladbar)
#
# Übersetzt wird nur, was ohne Android auskommt: die Bildschirme (Compose) und
# `UsbCanTransport`/`UsbDongleVerbindung`/`UsbSerialStrecke`/`FtdiStnVerbindung`
# brauchen `android.*` bzw. `usb-serial-for-android`. Keine Testklasse fasst sie
# an — sie sind am Gerät zu prüfen (docs/geraetepruefung.md), nicht hier.
#
# Pfade über Umgebungsvariablen:
#   JAVA_HOME    JDK mit `java` (Vorgabe: JAVA_HOME, sonst `java` aus dem PATH)
#   VCI_FFI_LIB  Verzeichnis mit libvci_ffi.so (Vorgabe: core-ffi/target/release)
#   OUT_DIR      Zielverzeichnis der Übersetzung (Vorgabe: /tmp/vci-jvm-tests)
#   JAR_DIR      Ablage für nachgeladene Jars (Vorgabe: /tmp/vci-jvm-jars)
#   MAVEN_MIRROR Maven-Spiegel (Vorgabe: https://repo1.maven.org/maven2)
#
# Ohne Bibliothek bricht das Skript ab, statt die drei Bindings-Testklassen still
# zu übergehen — ein übersprungener Test, der als „grün“ gilt, wäre schlimmer als
# keiner.

set -eu

cd "$(dirname "$0")/.."

GRADLE_CACHE=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
OUT_DIR=${OUT_DIR:-/tmp/vci-jvm-tests}
JAR_DIR=${JAR_DIR:-/tmp/vci-jvm-jars}
MAVEN_MIRROR=${MAVEN_MIRROR:-https://repo1.maven.org/maven2}
LIB_DIR=${VCI_FFI_LIB:-core-ffi/target/release}

# --- JDK -------------------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA=$JAVA_HOME/bin/java
else
    JAVA=$(command -v java || true)
fi
[ -n "$JAVA" ] || { echo "FEHLER: kein java gefunden (JAVA_HOME setzen)" >&2; exit 1; }

# --- native Bibliothek -----------------------------------------------------
LIB=$(cd "$LIB_DIR" 2>/dev/null && pwd || true)
if [ -z "$LIB" ] || [ ! -f "$LIB/libvci_ffi.so" ]; then
    echo "FEHLER: $LIB_DIR/libvci_ffi.so fehlt." >&2
    echo "        Host-Build erzeugen: cargo build --release --manifest-path core-ffi/Cargo.toml" >&2
    echo "        (Die Datei aus jniLibs ist für Android gebaut und hier nicht ladbar.)" >&2
    exit 1
fi
if readelf -d "$LIB/libvci_ffi.so" 2>/dev/null | grep -q 'libdl\.so$'; then
    echo "FEHLER: $LIB/libvci_ffi.so ist gegen Bionic gelinkt (libdl), nicht ladbar." >&2
    exit 1
fi

# --- Abhängigkeiten --------------------------------------------------------
# Erst im Gradle-Cache nachsehen, sonst von Maven holen. Die Versionen sind
# dieselben wie in `android/build.gradle.kts` (Kotlin 2.0.21), `android/app/
# build.gradle.kts` (JUnit 4.13.2, JNA 5.14.0) und der Kotlin-Standardbibliothek;
# bei einem Wechsel dort gehört die Tabelle hier mitgezogen.
need() { # group:artifact:version
    gruppe=${1%%:*}
    rest=${1#*:}
    artefakt=${rest%%:*}
    version=${rest#*:}
    datei="$artefakt-$version.jar"

    pfad=$(find "$GRADLE_CACHE" -name "$datei" -print 2>/dev/null | sort | head -1)
    if [ -n "$pfad" ]; then
        printf '%s' "$pfad"
        return 0
    fi

    ziel="$JAR_DIR/$datei"
    if [ ! -f "$ziel" ]; then
        url="$MAVEN_MIRROR/$(printf '%s' "$gruppe" | tr . /)/$artefakt/$version/$datei"
        mkdir -p "$JAR_DIR"
        echo "  lade $datei" >&2
        if ! curl -fsSL -o "$ziel.tmp" "$url"; then
            rm -f "$ziel.tmp"
            echo "FEHLER: $datei weder im Gradle-Cache noch unter $url" >&2
            return 1
        fi
        mv "$ziel.tmp" "$ziel"
    fi
    printf '%s' "$ziel"
}

KOTLINC=$(need org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21)
STDLIB=$(need org.jetbrains.kotlin:kotlin-stdlib:2.0.21)
SCRIPT_RT=$(need org.jetbrains.kotlin:kotlin-script-runtime:2.0.21)
DAEMON=$(need org.jetbrains.kotlin:kotlin-daemon-embeddable:2.0.21)
TROVE=$(need org.jetbrains.intellij.deps:trove4j:1.0.20200330)
ANNOT=$(need org.jetbrains:annotations:13.0)
COROUTINES=$(need org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4)
JUNIT=$(need junit:junit:4.13.2)
HAMCREST=$(need org.hamcrest:hamcrest-core:1.3)
JNA=$(need net.java.dev.jna:jna:5.14.0)

# --- Übersetzen ------------------------------------------------------------
# Die Bindings werden **mit** übersetzt, mit Ziel 17: die erzeugte Datei ist auf
# Java 21 voreingestellt, und ein älteres JDK kann sie sonst nicht laden.
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

MAIN=$(ls android/app/src/main/kotlin/de/werkstatt/vci/*.kt |
    grep -Ev '/(MainActivity|UsbAbschnitt|SfdAbschnitt|ObdLinkAbschnitt|BerichtTeilen)\.kt$')
TRANSPORT=$(ls android/app/src/main/kotlin/de/werkstatt/vci/transport/*.kt |
    grep -Ev '/(UsbDongleVerbindung|FtdiStnVerbindung|UsbSerialStrecke|BleGattVerbindung|UsbCanTransport)\.kt$')
TESTS=$(ls android/app/src/test/kotlin/de/werkstatt/vci/*.kt \
    android/app/src/test/kotlin/de/werkstatt/vci/transport/*.kt)

# shellcheck disable=SC2086
"$JAVA" -cp "$KOTLINC:$STDLIB:$SCRIPT_RT:$TROVE:$ANNOT:$DAEMON:$COROUTINES" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -nowarn \
    -cp "$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$JNA" \
    -d "$OUT_DIR" \
    core-ffi/bindings/kotlin/uniffi/vci_ffi/vci_ffi.kt \
    $MAIN $TRANSPORT $TESTS || {
    echo "FEHLER: Übersetzung fehlgeschlagen" >&2
    exit 1
}

# --- Starten ---------------------------------------------------------------
CLASSES=$(cd "$OUT_DIR" && find de/werkstatt/vci -name '*Test.class' |
    grep -v '\$' | sed 's|\.class$||; s|/|.|g' | sort)

echo "Bibliothek: $LIB"
echo "Tests:      $(printf '%s\n' $CLASSES | wc -l) Klassen"
# shellcheck disable=SC2086
"$JAVA" -Djna.library.path="$LIB" \
    -cp "$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$JNA:$OUT_DIR" \
    org.junit.runner.JUnitCore $CLASSES
