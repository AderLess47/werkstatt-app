#!/bin/sh
# Bereitet diese Maschine auf Tests und APK-Build vor — nach einem
# Sandbox-Restore in einem Schritt:
#
#   JDK 17 · cargo im PATH · rustfmt (Formatprüfung des Hakens)
#   Host-Bibliothek des Kerns (für die JVM-Tests)
#   Android SDK (Platform 34, Build-Tools 34.0.0) · NDK 26.1.10909125
#   Gradle 8.9 · die Android-Bibliothek in jniLibs (für den APK-Build)
#
#     sh scripts/vorbereiten.sh                  # alles
#     sh scripts/vorbereiten.sh --probe          # nur zeigen, was fehlt
#     OHNE_ANDROID=1 sh scripts/vorbereiten.sh   # nur die JVM-Tests
#
# Was ein Restore kostet, und was hier zurückkommt:
#
#   /tmp ist leer       → das JDK fehlt; es wird von Adoptium geholt (Temurin 17)
#   ~/.gradle fehlt     → die Jars holt scripts/jvm-tests.sh selbst von Maven
#   target/ ist weg     → die Host-Bibliothek wird neu gebaut (cargo)
#   ~/android-sdk weg   → cmdline-tools, Platform, Build-Tools und NDK kommen
#                         von dl.google.com, Gradle von gradle.org
#   jniLibs ist weg     → die beiden Android-Bibliotheken entstehen aus dem
#                         Quellcode (nicht versioniert, siehe
#                         docs/android-app-modul.md)
#
# Pfade über Umgebungsvariablen:
#   JDK_DIR          Ziel des JDK (Vorgabe: /tmp/jdk)
#   JDK_ARCHIV       Ablage des JDK-Archivs (Vorgabe: /tmp/temurin-jdk.tar.gz)
#   JAVA_MAJOR       geforderte Hauptversion (Vorgabe: 17)
#   SDK_DIR          Android SDK (Vorgabe: ~/android-sdk)
#   ANDROID_PLATTFORM  Platform-API (Vorgabe: 34)
#   BUILD_TOOLS      Build-Tools (Vorgabe: 34.0.0)
#   NDK_VERSION      NDK (Vorgabe: 26.1.10909125)
#   GRADLE_VERSION   Gradle (Vorgabe: aus android/gradle/wrapper/gradle-wrapper.properties)
#   GRADLE_DIR       Ziel von Gradle (Vorgabe: ~/.local/gradle-$GRADLE_VERSION)
#   OHNE_ANDROID=1   überspringt SDK, NDK, Gradle und jniLibs

set -eu

cd "$(dirname "$0")/.."

JDK_DIR=${JDK_DIR:-/tmp/jdk}
JDK_ARCHIV=${JDK_ARCHIV:-/tmp/temurin-jdk.tar.gz}
JAVA_MAJOR=${JAVA_MAJOR:-17}
SDK_DIR=${SDK_DIR:-$HOME/android-sdk}
ANDROID_PLATTFORM=${ANDROID_PLATTFORM:-34}
BUILD_TOOLS=${BUILD_TOOLS:-34.0.0}
NDK_VERSION=${NDK_VERSION:-26.1.10909125}
WRAPPER_PROPS=android/gradle/wrapper/gradle-wrapper.properties
# GRADLE_VERSION und GRADLE_DIR werden weiter unten bestimmt — die Version
# steht im Wrapper der App, damit es nur eine Zahl im Projekt gibt.
OHNE_ANDROID=${OHNE_ANDROID:-0}
LIB=core-ffi/target/release/libvci_ffi.so
JNI=android/app/src/main/jniLibs
PROBE=0

if [ "${1:-}" = "--probe" ]; then
    PROBE=1
fi

fehler() {
    echo "FEHLER: $*" >&2
    exit 1
}

# Meldet einen fehlenden Baustein im Trockenlauf, statt ihn zu holen.
wuerde_holen() {
    echo "  fehlt: $*"
}

# Die Gradle-Version steht im Wrapper (`distributionUrl`), nicht hier: CI und
# dieses Skript lesen dieselbe Datei. Fehlt sie, ist das ein Fehler und kein
# stiller Rückfall auf irgendeine Version.
if [ -z "${GRADLE_VERSION:-}" ]; then
    GRADLE_VERSION=$(sed -n 's|.*distributions/gradle-\([0-9.]*\)-bin\.zip.*|\1|p' \
        "$WRAPPER_PROPS" 2>/dev/null | head -1)
    [ -n "$GRADLE_VERSION" ] ||
        fehler "keine Gradle-Version in $WRAPPER_PROPS gefunden"
fi
GRADLE_DIR=${GRADLE_DIR:-$HOME/.local/gradle-$GRADLE_VERSION}

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

# --- rustfmt ----------------------------------------------------------------
# Der Pre-Push-Haken prüft das ganze Crate auf Formatierung (`cargo fmt
# --check` für `core`, `core-ffi`, `core/fuzz`), und ohne `rustfmt` lässt er
# den Schritt aus — sichtbar, aber ein frischer Arbeitsplatz soll ihn gar nicht
# erst überspringen müssen.
#
# Geprüft wird mit einem echten Aufruf und nicht mit `command -v`: rustup legt
# die Proxy-Binaries in ~/.cargo/bin unabhängig von den Komponenten ab. Fehlt
# die Komponente, findet `command -v` den Proxy trotzdem — und `rustfmt
# --check` endet dann mit einem Fehler, den der Haken als „nicht formatiert“
# liest und jede Datei rot meldet. Der Aufruf unterscheidet die beiden Fälle.
rustfmt_ok() {
    rustfmt --version > /dev/null 2>&1
}

if rustfmt_ok; then
    echo "rustfmt: $(rustfmt --version 2>/dev/null | head -1)"
elif [ "$PROBE" -eq 1 ]; then
    wuerde_holen "rustfmt (rustup component add rustfmt)"
else
    # rustup holt die Komponente passend zur aktiven Toolchain. Ohne rustup
    # (Cargo aus der Distribution) bleibt nur der Hinweis.
    if command -v rustup > /dev/null; then
        rustup component add rustfmt > /dev/null ||
            fehler "rustup component add rustfmt fehlgeschlagen"
    fi
    rustfmt_ok ||
        fehler "rustfmt fehlt — rustup component add rustfmt (sonst überspringt der Pre-Push-Haken die Formatprüfung)"
    echo "rustfmt: $(rustfmt --version 2>/dev/null | head -1)"
fi

# --- JDK --------------------------------------------------------------------
jdk_ok() {
    [ -x "$JDK_DIR/bin/java" ] || return 1
    "$JDK_DIR/bin/java" -version 2>&1 | head -1 | grep -q "\"$JAVA_MAJOR" || return 1
}

if jdk_ok; then
    echo "JDK:    $JDK_DIR — $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
elif [ "$PROBE" -eq 1 ]; then
    wuerde_holen "JDK $JAVA_MAJOR unter $JDK_DIR"
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

# --- Host-Bibliothek (JVM-Tests) --------------------------------------------
# Gebaut wird, wenn sie fehlt oder wenn Quellen bzw. Manifeste neuer sind. Ohne
# diese Prüfung würde ein Restore, bei dem `target/` überlebt hat, eine
# veraltete Bibliothek stehen lassen — und die Tests würden alten Code prüfen.
neuer_als() {
    # $1 = Datei, gegen die verglichen wird; gibt den ersten neueren Treffer
    # aus (leer = nichts neuer).
    #
    # `target/` wird beschnitten: dort erzeugen Build-Skripte bei jedem Lauf
    # .rs-Dateien, die dann neuer wären als jede Bibliothek — ohne das Prune
    # meldet das Skript nach jedem Bauen wieder „veraltet“.
    [ -e "$1" ] || return 0
    find core core-ffi -path '*/target' -prune -o \
        \( -name '*.rs' -o -name 'Cargo.toml' -o -name 'Cargo.lock' \) \
        -newer "$1" -print -quit
}

if [ ! -f "$LIB" ]; then
    if [ "$PROBE" -eq 1 ]; then
        wuerde_holen "Host-Bibliothek $LIB"
    else
        echo "Kern:   baue Host-Bibliothek (cargo build --release --locked) …"
        cargo build --release --locked --manifest-path core-ffi/Cargo.toml ||
            fehler "cargo build fehlgeschlagen"
    fi
elif [ -n "$(neuer_als "$LIB")" ]; then
    if [ "$PROBE" -eq 1 ]; then
        wuerde_holen "Host-Bibliothek ist veraltet ($LIB)"
    else
        echo "Kern:   Quellen sind neuer als die Bibliothek — baue neu"
        cargo build --release --locked --manifest-path core-ffi/Cargo.toml ||
            fehler "cargo build fehlgeschlagen"
    fi
else
    echo "Kern:   $LIB ist aktuell"
fi

if [ -f "$LIB" ]; then
    # Die Bibliothek aus jniLibs ist für Android gebaut (Bionic). Wer sie hier
    # lädt, bekommt einen dlopen-Fehler, der wie ein Testfehler aussieht.
    if readelf -d "$LIB" | grep -q 'libdl\.so'; then
        fehler "$LIB ist gegen Bionic gelinkt (Android) und auf dieser Maschine nicht ladbar"
    fi
fi

# --- Android: SDK, NDK, Gradle, jniLibs -------------------------------------
if [ "$OHNE_ANDROID" != "0" ]; then
    echo "Android: übersprungen (OHNE_ANDROID gesetzt) — kein APK-Build"
else
    command -v unzip > /dev/null ||
        fehler "unzip fehlt — wird für SDK, NDK und Gradle gebraucht"

    # cmdline-tools: ohne sdkmanager lässt sich kein Paket holen.
    SDKM="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$SDKM" ]; then
        if [ "$PROBE" -eq 1 ]; then
            wuerde_holen "cmdline-tools unter $SDK_DIR"
        else
            echo "Android: lade cmdline-tools …"
            mkdir -p "$SDK_DIR" /tmp/vci-sdk
            curl -fsSL --retry 2 -o /tmp/vci-sdk/cmdline-tools.zip \
                https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip ||
                fehler "Download der cmdline-tools fehlgeschlagen"
            rm -rf /tmp/vci-sdk/auspack /tmp/vci-sdk/cmdline-tools
            unzip -q -o /tmp/vci-sdk/cmdline-tools.zip -d /tmp/vci-sdk/auspack ||
                fehler "Entpacken der cmdline-tools fehlgeschlagen"
            mkdir -p "$SDK_DIR/cmdline-tools"
            mv /tmp/vci-sdk/auspack/cmdline-tools "$SDK_DIR/cmdline-tools/latest" ||
                fehler "cmdline-tools ließen sich nicht ablegen"
        fi
    fi

    # Fehlende Pakete sammeln und in einem Aufruf holen — sdkmanager löst
    # Abhängigkeiten (Platform bringt die android.jar mit) selbst auf.
    pakete=""
    if [ ! -d "$SDK_DIR/platforms/android-$ANDROID_PLATTFORM" ]; then
        pakete="$pakete platforms;android-$ANDROID_PLATTFORM"
    fi
    if [ ! -d "$SDK_DIR/build-tools/$BUILD_TOOLS" ]; then
        pakete="$pakete build-tools;$BUILD_TOOLS"
    fi
    if [ ! -d "$SDK_DIR/ndk/$NDK_VERSION" ]; then
        pakete="$pakete ndk;$NDK_VERSION"
    fi

    if [ -n "$pakete" ]; then
        if [ "$PROBE" -eq 1 ]; then
            wuerde_holen "Android-Pakete:$pakete"
        else
            echo "Android: lade$pakete …"
            # `yes |` beantwortet die Lizenzfrage; Rückgabewert ist danach
            # unzuverlässig, deshalb ohne Prüfung.
            ( yes | "$SDKM" --sdk_root="$SDK_DIR" --licenses > /dev/null 2>&1 ) || true
            # Absichtlich unquotiert: die Liste soll in Wörter zerfallen.
            "$SDKM" --sdk_root="$SDK_DIR" $pakete > /dev/null ||
                fehler "sdkmanager konnte$pakete nicht installieren"
            [ -d "$SDK_DIR/platforms/android-$ANDROID_PLATTFORM" ] ||
                fehler "Platform $ANDROID_PLATTFORM fehlt nach der Installation"
            [ -d "$SDK_DIR/build-tools/$BUILD_TOOLS" ] ||
                fehler "Build-Tools $BUILD_TOOLS fehlen nach der Installation"
            [ -d "$SDK_DIR/ndk/$NDK_VERSION" ] ||
                fehler "NDK $NDK_VERSION fehlt nach der Installation"
        fi
    else
        echo "Android: Platform $ANDROID_PLATTFORM, Build-Tools $BUILD_TOOLS, NDK $NDK_VERSION vorhanden"
    fi

    # AGP liest den SDK-Pfad aus local.properties und fällt nur dann auf
    # ANDROID_HOME zurück, wenn die Datei fehlt — ein alter Pfad darin führt
    # also zum Abbruch, nicht zur Suche.
    if [ "$PROBE" -eq 0 ] && [ -x "$SDKM" ]; then
        if [ "$(cat android/local.properties 2>/dev/null)" != "sdk.dir=$SDK_DIR" ]; then
            echo "sdk.dir=$SDK_DIR" > android/local.properties
            echo "Android: android/local.properties → $SDK_DIR"
        fi
    fi

    # Gradle: die Version kommt aus dem Wrapper, das Repository hat aber keinen
    # mitgelieferten Wrapper (die .jar ist binär und wird nicht eingecheckt).
    if [ -x "$GRADLE_DIR/bin/gradle" ]; then
        # Gradle braucht selbst ein JDK; ohne JAVA_HOME schweigt `--version`.
        gradle_version=$(JAVA_HOME="$JDK_DIR" "$GRADLE_DIR/bin/gradle" --version 2>/dev/null |
            awk '/^Gradle /{print $2; exit}' || true)
        echo "Gradle: $GRADLE_DIR${gradle_version:+ — $gradle_version}"
    elif [ "$PROBE" -eq 1 ]; then
        wuerde_holen "Gradle $GRADLE_VERSION unter $GRADLE_DIR"
    else
        echo "Gradle: fehlt — lade $GRADLE_VERSION …"
        mkdir -p "$HOME/.local" /tmp/vci-sdk
        curl -fsSL --retry 2 -o /tmp/vci-sdk/gradle.zip \
            "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" ||
            fehler "Download von Gradle fehlgeschlagen"
        unzip -q -o /tmp/vci-sdk/gradle.zip -d "$HOME/.local" ||
            fehler "Entpacken von Gradle fehlgeschlagen"
        [ -x "$GRADLE_DIR/bin/gradle" ] ||
            fehler "Gradle liegt nicht unter $GRADLE_DIR"
        echo "Gradle: $GRADLE_DIR"
    fi

    # jniLibs: die Bibliothek ist nicht versioniert, sie entsteht aus dem
    # Quellcode — mit dem NDK-Compiler des SDK, für beide ABIs.
    if [ -x "$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" ]; then
        echo "jniLibs:"
        for paar in "aarch64-linux-android arm64-v8a" "x86_64-linux-android x86_64"; do
            # shellcheck disable=SC2086
            set -- $paar
            ziel=$1
            abi=$2
            datei="$JNI/$abi/libvci_ffi.so"
            bauen=0
            if [ ! -f "$datei" ]; then
                bauen=1
                grund="fehlt"
            elif [ -n "$(neuer_als "$datei")" ]; then
                bauen=1
                grund="Quellen sind neuer"
            fi

            if [ "$bauen" -eq 0 ]; then
                echo "  $abi: aktuell ($(stat -c%s "$datei") B)"
                continue
            fi
            if [ "$PROBE" -eq 1 ]; then
                echo "  $abi: $grund — würde bauen"
                continue
            fi

            # Das Rust-Ziel muss installiert sein. Geprüft wird im sysroot und
            # nicht über `rustup target list --installed`: nach einem Restore
            # liegt die std-Bibliothek da, während rustups Buchhaltung sie
            # nicht kennt — `rustup target add` bricht dann mit „detected
            # conflict“ ab, obwohl das Ziel benutzbar ist.
            sysroot=$(rustc --print sysroot)
            if [ ! -d "$sysroot/lib/rustlib/$ziel/lib" ]; then
                if command -v rustup > /dev/null; then
                    rustup target add "$ziel" || true
                fi
                [ -d "$sysroot/lib/rustlib/$ziel/lib" ] ||
                    fehler "Rust-Ziel $ziel fehlt (rustup target add $ziel)"
            fi

            linker="$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/${ziel}29-clang"
            # API 29 = minSdk der App; ein älterer Linker würde gegen eine
            # ältere Bionic binden.
            echo "  $abi: $grund — baue ($ziel) …"
            env "$(echo "$ziel" | tr 'a-z-' 'A-Z_')_LINKER=$linker" \
                "CARGO_TARGET_$(echo "$ziel" | tr 'a-z-' 'A-Z_')_LINKER=$linker" \
                cargo build --release --locked --target "$ziel" \
                --manifest-path core-ffi/Cargo.toml ||
                fehler "cargo build für $ziel fehlgeschlagen"
            mkdir -p "$JNI/$abi"
            cp "core-ffi/target/$ziel/release/libvci_ffi.so" "$datei"
            # Gegenprobe: eine Android-Bibliothek braucht libdl (Bionic).
            readelf -d "$datei" | grep -q 'libdl\.so' ||
                fehler "$datei ist nicht gegen Bionic gelinkt — falscher Linker?"
            echo "  $abi: $(stat -c%s "$datei") B, libdl ✓"
        done
    elif [ "$PROBE" -eq 1 ]; then
        wuerde_holen "NDK, dann die Bibliotheken in $JNI"
    else
        fehler "NDK-Werkzeuge fehlen unter $SDK_DIR/ndk/$NDK_VERSION"
    fi
fi

# --- wie weiter -------------------------------------------------------------
cat <<HINWEIS

Fertig. Für die nächsten Befehle in dieser Shell:
  export JAVA_HOME=$JDK_DIR
  export PATH="\$HOME/.cargo/bin:\$PATH"

Tests:
  sh scripts/kern-tests.sh    # C-Kern, Korpus, Rust-Kern, FFI-Schicht
  sh scripts/jvm-tests.sh     # 174 JVM-Tests der App
HINWEIS

if [ "$OHNE_ANDROID" = "0" ]; then
    cat <<HINWEIS

APK bauen:
  export ANDROID_HOME=$SDK_DIR
  export PATH="$GRADLE_DIR/bin:\$PATH"
  gradle -p android :app:testDebugUnitTest :app:assembleDebug
  # → android/app/build/outputs/apk/debug/app-debug.apk

Aufs Tablet kommt sie vom Rechner mit dem Kabel (adb install -r app-debug.apk);
diese VM hat keinen Zugriff auf USB-Geräte.
HINWEIS
fi
