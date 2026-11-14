#!/bin/sh
# Führt die JVM-Tests von :app ohne Gradle und ohne Android-Gerät aus.
#
# Warum es das gibt: in dieser Umgebung fehlt das Android SDK, also läuft
# `:app:testDebugUnitTest` nicht. Die Tests sind aber gewöhnliche JUnit-4-Tests
# und brauchen nur drei Dinge:
#
#   1. einen Kotlin-Compiler (aus dem Gradle-Cache, kein Netz nötig),
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
# Pfade über Umgebungsvariablen:
#   JAVA_HOME    JDK mit `java` (Vorgabe: JAVA_HOME, sonst `java` aus dem PATH)
#   VCI_FFI_LIB  Verzeichnis mit libvci_ffi.so (Vorgabe: core-ffi/target/release)
#   ANDROID_JAR  android.jar für die USB-Klassen (Vorgabe: $ANDROID_HOME/…)
#   OUT_DIR      Zielverzeichnis der Übersetzung (Vorgabe: /tmp/vci-jvm-tests)
#
# Ohne Bibliothek bricht das Skript ab, statt die drei Bindings-Testklassen still
# zu übergehen — ein übersprungener Test, der als „grün" gilt, wäre schlimmer als
# keiner.

set -eu

cd "$(dirname "$0")/.."

GRADLE_CACHE=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
OUT_DIR=${OUT_DIR:-/tmp/vci-jvm-tests}
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
if readelf -d "$LIB/libvci_ffi.so" 2>/dev/null | grep -q 'libdl\.so$\|libdl\.so\]'; then
    echo "FEHLER: $LIB/libvci_ffi.so ist gegen Bionic gelinkt (libdl), nicht ladbar." >&2
    exit 1
fi

# --- Abhängigkeiten aus dem Gradle-Cache -----------------------------------
finder() {
    # Mehrere Versionen und Quellarchive liegen im Cache; gebraucht wird das
    # letzte echte Jar in Sortierreihenfolge (die höchste Version).
    find "$GRADLE_CACHE" -name "$1" -print 2>/dev/null |
        grep -Ev -- '-(sources|javadoc)\.jar$' | sort | tail -1
}

need() {
    p=$(finder "$1")
    [ -n "$p" ] || { echo "FEHLER: $1 nicht im Gradle-Cache — einmal ':app' bauen." >&2; exit 1; }
    printf '%s' "$p"
}

KOTLINC_JARS=$(need 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(need 'kotlin-stdlib-2*.jar')
SCRIPT_RT=$(need 'kotlin-script-runtime-*.jar')
TROVE=$(need 'trove4j-*.jar')
ANNOT=$(need 'annotations-13.0.jar')
DAEMON=$(need 'kotlin-daemon-embeddable-*.jar')
COROUTINES=$(need 'kotlinx-coroutines-core-jvm-*.jar')
JUNIT=$(need 'junit-4.13.2.jar')
HAMCREST=$(need 'hamcrest-core-1.3.jar')
JNA=$(need 'jna-5.14.0.jar')

ANDROID_JAR=${ANDROID_JAR:-${ANDROID_HOME:-$HOME/android-sdk}/platforms/android-34/android.jar}
[ -f "$ANDROID_JAR" ] || { echo "FEHLER: android.jar fehlt ($ANDROID_JAR)" >&2; exit 1; }

# --- Übersetzen ------------------------------------------------------------
# Die Bindings werden **mit** übersetzt, mit Ziel 17: die erzeugte Datei ist auf
# Java 21 voreingestellt, und ein älteres JDK kann sie sonst nicht laden.
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Nicht dabei: alles, was Compose oder usb-serial-for-android braucht — dafür
# fehlt hier das SDK. Das sind Oberflächen, ihre Logik steckt in den getesteten
# Klassen.
MAIN=$(ls android/app/src/main/kotlin/de/werkstatt/vci/*.kt |
    grep -Ev '/(MainActivity|UsbAbschnitt|ObdLinkAbschnitt|BerichtTeilen)\.kt$')
TRANSPORT=$(ls android/app/src/main/kotlin/de/werkstatt/vci/transport/*.kt |
    grep -Ev '/(UsbDongleVerbindung|FtdiStnVerbindung|UsbSerialStrecke|BleGattVerbindung)\.kt$')
TESTS=$(ls android/app/src/test/kotlin/de/werkstatt/vci/*.kt \
    android/app/src/test/kotlin/de/werkstatt/vci/transport/*.kt)

# shellcheck disable=SC2086
"$JAVA" -cp "$KOTLINC_JARS:$STDLIB:$SCRIPT_RT:$TROVE:$ANNOT:$DAEMON:$COROUTINES" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -nowarn \
    -cp "$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$JNA:$ANDROID_JAR" \
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
    -cp "$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$JNA:$ANDROID_JAR:$OUT_DIR" \
    org.junit.runner.JUnitCore $CLASSES
