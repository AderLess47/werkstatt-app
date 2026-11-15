#!/bin/sh
# Bereitet diese Maschine auf die Tests vor — nach einem Sandbox-Restore in
# einem Schritt: JDK 17, cargo im PATH, Host-Bibliothek des Kerns.
#
#     sh scripts/vorbereiten.sh
#
# Was ein Restore kostet, und was hier zurückkommt:
#
#   /tmp ist leer    → das JDK fehlt; es wird von Adoptium geholt (Temurin 17)
#   ~/.gradle fehlt  → die Jars holt scripts/jvm-tests.sh selbst von Maven
#   target/ ist weg  → die Host-Bibliothek wird neu gebaut (cargo)
#
# Bewusst **nicht** hier: Android SDK und NDK. Für die JVM-Tests werden sie nicht
# gebraucht, und `:app:assembleDebug` ist Sache der CI bzw. eines Rechners mit
# SDK — ein halb installiertes SDK würde nur so tun, als wäre die App baubar.
#
# Pfade über Umgebungsvariablen:
#   JDK_DIR    Ziel des JDK (Vorgabe: /tmp/jdk)
#   JDK_ARCHIV Ablage des Archivs (Vorgabe: /tmp/temurin-jdk.tar.gz)
#   JAVA_MAJOR geforderte Hauptversion (Vorgabe: 17)

set -eu

cd "$(dirname "$0")/.."

JDK_DIR=${JDK_DIR:-/tmp/jdk}
JDK_ARCHIV=${JDK_ARCHIV:-/tmp/temurin-jdk.tar.gz}
JAVA_MAJOR=${JAVA_MAJOR:-17}
LIB=core-ffi/target/release/libvci_ffi.so

fehler() {
    echo "FEHLER: $*" >&2
    exit 1
}

# --- cargo ------------------------------------------------------------------
# rustup legt cargo nach ~/.cargo/bin und trägt es nur in Login-Shells in den
# PATH ein. Ein nicht-interaktiver Lauf sieht es deshalb oft nicht.
if [ -x "$HOME/.cargo/bin/cargo" ]; then
    PATH="$HOME/.cargo/bin:$PATH"
    export PATH
fi
command -v cargo > /dev/null ||
    fehler "cargo fehlt — rustup installieren (https://rustup.rs) oder ~/.cargo/bin in den PATH legen"
echo "cargo:  $(cargo --version)"

# --- JDK --------------------------------------------------------------------
jdk_ok() {
    [ -x "$JDK_DIR/bin/java" ] || return 1
    "$JDK_DIR/bin/java" -version 2>&1 | head -1 | grep -q "\"$JAVA_MAJOR" || return 1
}

if jdk_ok; then
    echo "JDK:    $JDK_DIR — $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
else
    case "$(uname -m)" in
        x86_64 | amd64) arch=x64 ;;
        aarch64 | arm64) arch=aarch64 ;;
        *) fehler "unbekannte Architektur: $(uname -m)" ;;
    esac
    URL="https://api.adoptium.net/v3/binary/latest/$JAVA_MAJOR/ga/linux/$arch/jdk/hotspot/normal/eclipse?project=jdk"
    echo "JDK:    fehlt — lade Temurin $JAVA_MAJOR ($arch) …"
    curl -fsSL --retry 2 -o "$JDK_ARCHIV" "$URL" ||
        fehler "Download fehlgeschlagen: $URL"
    rm -rf "$JDK_DIR"
    mkdir -p "$JDK_DIR"
    tar xzf "$JDK_ARCHIV" -C "$JDK_DIR" --strip-components=1 ||
        fehler "Entpacken fehlgeschlagen: $JDK_ARCHIV"
    jdk_ok || fehler "JDK in $JDK_DIR läuft nicht ($("$JDK_DIR/bin/java" -version 2>&1 | head -1))"
    echo "JDK:    $JDK_DIR — $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
fi

# --- Host-Bibliothek --------------------------------------------------------
# Gebaut wird, wenn sie fehlt oder wenn Quellen bzw. Manifeste neuer sind. Ohne
# diese Prüfung würde ein Restore, bei dem `target/` überlebt hat, eine
# veraltete Bibliothek stehen lassen — und die Tests würden alten Code prüfen.
bauen=0
if [ ! -f "$LIB" ]; then
    bauen=1
elif [ -n "$(find core core-ffi \( -name '*.rs' -o -name 'Cargo.toml' -o -name 'Cargo.lock' \) \
    -newer "$LIB" -print -quit)" ]; then
    bauen=1
    echo "Kern:   Quellen sind neuer als die Bibliothek — baue neu"
fi

if [ "$bauen" -eq 1 ]; then
    echo "Kern:   baue Host-Bibliothek (cargo build --release --locked) …"
    cargo build --release --locked --manifest-path core-ffi/Cargo.toml ||
        fehler "cargo build fehlgeschlagen"
else
    echo "Kern:   $LIB ist aktuell"
fi

# Die Bibliothek aus jniLibs ist für Android gebaut (Bionic). Wer sie hier
# lädt, bekommt einen dlopen-Fehler, der wie ein Testfehler aussieht.
if readelf -d "$LIB" | grep -q 'libdl\.so'; then
    fehler "$LIB ist gegen Bionic gelinkt (Android) und auf dieser Maschine nicht ladbar"
fi

# --- wie weiter -------------------------------------------------------------
cat <<HINWEIS

Fertig. Für die nächsten Befehle in dieser Shell:
  export JAVA_HOME=$JDK_DIR
  export PATH="\$HOME/.cargo/bin:\$PATH"

Tests:
  sh scripts/kern-tests.sh    # C-Kern, Korpus, Rust-Kern, FFI-Schicht
  sh scripts/jvm-tests.sh     # 119 JVM-Tests der App
HINWEIS
