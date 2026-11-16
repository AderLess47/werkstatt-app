#!/bin/sh
# Setzt model/wirtschaftlichkeit.py aus zwei Byte-Teilen zusammen.
#
# Warum Teile: die GitHub-API nimmt eine Datei nur am Stueck entgegen, und ein
# Aufruf mit 44 KB wird abgeschnitten (beobachtete Grenze rund 30 KB). Die Teile
# sind exakte Byte-Schnitte an der Zeilengrenze 450 — cat ergibt die
# Originaldatei. Der Blob-Hash beweist es, deshalb prueft das Skript ihn.
#
# Aufruf aus dem Wurzelverzeichnis:  sh scripts/modell-bauen.sh
set -eu
cd "$(dirname "$0")/.."

cat model/wirtschaftlichkeit.py.part1 model/wirtschaftlichkeit.py.part2 \
    > model/wirtschaftlichkeit.py

ist=$(git hash-object model/wirtschaftlichkeit.py)
soll=08939e9966170e5ae1de8d1f2f05e055d91ffc2d

echo "geschrieben: model/wirtschaftlichkeit.py ($(wc -c < model/wirtschaftlichkeit.py) Bytes)"
echo "Blob:     $ist"
echo "erwartet: $soll"

if [ "$ist" != "$soll" ]; then
    echo "ABWEICHUNG — Teile pruefen, nicht weiterarbeiten." >&2
    exit 1
fi

echo "stimmt: zusammengesetzt ist die Datei byte-identisch mit dem Original."
