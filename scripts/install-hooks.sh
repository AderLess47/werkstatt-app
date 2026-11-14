#!/bin/sh
# Hängt die Hooks aus .githooks in dieses Repository ein.
#
# Warum über core.hooksPath und nicht durch Kopieren nach .git/hooks: die Datei
# liegt damit im Repository und wandert mit jedem `git pull` mit. Eine Kopie in
# .git/hooks veraltet still und ist beim nächsten Rechner wieder weg.

set -eu

cd "$(dirname "$0")/.."

git rev-parse --git-dir > /dev/null 2>&1 || {
    echo "FEHLER: hier ist kein Git-Repository." >&2
    exit 1
}

git config core.hooksPath .githooks
# Das Ausführbar-Bit kommt nicht überall mit (Archiv, ZIP, Windows-Checkout).
chmod +x .githooks/pre-push

echo "Hooks eingehängt: core.hooksPath=.githooks"
echo "Vor jedem Push laufen die JVM-Tests (sh scripts/jvm-tests.sh, etwa 20 s)."
echo "Abschalten im Notfall: VCI_SKIP_HOOK=1 git push …"
echo
echo "Voraussetzung ist der Host-Build der Bibliothek, sonst bricht der Hook mit"
echo "einer Anleitung ab:  cargo build --release --manifest-path core-ffi/Cargo.toml"
