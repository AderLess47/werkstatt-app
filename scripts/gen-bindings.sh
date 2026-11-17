#!/usr/bin/env bash
#
# Erzeugt die Kotlin- und Swift-Bindings des App-Kerns.
#
#   ./scripts/gen-bindings.sh              # kotlin und swift für den Host
#   ./scripts/gen-bindings.sh kotlin       # nur Kotlin
#
# Die Dateien unter core-ffi/bindings/ sind erzeugt und gehören nicht ins
# Repository (siehe .gitignore). Sie werden in der CI erzeugt und als Artefakt
# verteilt, damit sie nicht von Hand gepflegt werden können (ADR-001 §5).
#
# Für echte Geräte muss die Bibliothek für die Ziel-ABIs gebaut werden:
#   Android: cargo ndk -t arm64-v8a -t x86_64 -o app-android/app/src/main/jniLibs build --release
#   iOS:     cargo build --release --target aarch64-apple-ios
#            cargo build --release --target aarch64-apple-ios-sim
#            (danach beide in ein XCFramework; nur auf macOS möglich)
# Dieser Skript-Schritt erzeugt nur die Bindings für die Host-Bibliothek —
# genug, um die Schnittstelle zu prüfen, nicht um die App zu linken.

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

MANIFEST="core-ffi/Cargo.toml"
OUT="core-ffi/bindings"
LANGS="${*:-kotlin swift}"

if ! command -v cargo >/dev/null 2>&1; then
    if [ -x "$HOME/.cargo/bin/cargo" ]; then
        export PATH="$HOME/.cargo/bin:$PATH"
    else
        echo "cargo fehlt — bitte https://rustup.rs installieren" >&2
        exit 1
    fi
fi

echo "==> baue vci-ffi (release)"
cargo build --manifest-path "$MANIFEST" --release --locked

case "$(uname -s)" in
    Linux)  LIB="core-ffi/target/release/libvci_ffi.so" ;;
    Darwin) LIB="core-ffi/target/release/libvci_ffi.dylib" ;;
    *)      LIB="core-ffi/target/release/vci_ffi.dll" ;;
esac

if [ ! -f "$LIB" ]; then
    echo "Bibliothek nicht gefunden: $LIB" >&2
    exit 1
fi

# Bindgen läuft im Crate-Verzeichnis: es ruft selbst `cargo metadata` auf und
# findet das Manifest nur, wenn das Arbeitsverzeichnis im Crate liegt.
for lang in $LANGS; do
    echo "==> $lang"
    ( cd core-ffi && cargo run --quiet --release --locked --bin uniffi-bindgen -- \
        generate --library "$ROOT/$LIB" --language "$lang" --out-dir "$ROOT/$OUT/$lang" )
done

echo "==> fertig: $OUT"
find "$OUT" -type f | sed 's/^/    /'
