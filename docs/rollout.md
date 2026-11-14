# Rollout-Werkzeug — VCI-Prüfung über eine Werkstattkette

**Stand:** 14.11.2026 · Code: [`j2534/src/rollout.c`](../j2534/src/rollout.c) · CLI: [`j2534/tools/rollout.c`](../j2534/tools/rollout.c)
**Baut auf:** [VCI-Checker](vci-checker.md)

---

## 1. Das Problem, das es löst

Ein Rollout scheitert selten an der Technik, sondern an der Nachweisbarkeit. Bei 300 Geräten in 40 Betrieben weiß nach drei Wochen niemand mehr, welche Geräte geprüft wurden, welche noch die alte Firmware haben und welcher Standort eigentlich nie geprüft hat. Genau diese drei Fragen beantwortet das Werkzeug — nicht mehr.

Der Kern der Aufteilung: **Prüfen kann nur vor Ort passieren**, weil das VCI am Rechner hängt. **Auswerten passiert zentral.** Dazwischen liegt eine Datei, die man per Mail oder Freigabe einsammelt.

---

## 2. Ablauf

```bash
# 1. Vor Ort — jedes Gerät einmal
rollout run --site "Filiale Nord" --device VCI-01 --serial SN-A1

# 2. Vor Ort — nächster Betrieb, gleiche Sammeldatei
rollout run --site "Filiale Süd"  --device VCI-02 --serial SN-A2

# 3. Zentral — eingesammelte Berichte laut Plan übernehmen
rollout import --plan plan.csv

# 4. Zentral — auswerten
rollout report --in rollout-results.jsonl --min-pass 80
```

`run` prüft, hängt eine Zeile an die Sammeldatei und gibt den Prüfstatus als Exit-Code zurück (0/1/2) — derselbe Code wie der Checker. Damit kann ein Techniker vor Ort sofort sehen, ob das Gerät in den Betrieb darf.

### Plan-CSV

```
standort;geraet;seriennummer;pfad/zum/bericht.json
Filiale West;VCI-04;SN-A4;berichte/vci-04.json
```

Der Plan ist die einzige Stelle, an der die Zuordnung Gerät → Standort gepflegt wird. Zeilen mit `#` sind Kommentare; fehlende oder unlesbare Berichte werden gemeldet und gezählt, nicht stillschweigend übergangen.

---

## 3. Die Sammeldatei

JSON Lines, eine Zeile pro Lauf:

```json
{"site":"Filiale Nord","device":"VCI-01","serial":"SN-A1",
 "timestamp":"2026-11-14T09:14:02Z","firmware":"1.4","verdict":"ok",
 "resyncs":0,"crc_errors":0,
 "checks":[{"id":"device","status":"ok"},{"id":"vbat","status":"ok"}, ...]}
```

Nur diese Felder werden gespeichert: Standort, Gerät, Seriennummer, Zeitpunkt, Messergebnis. **Keine Fahrzeug-, Kunden- oder Auftragsdaten.** Damit ist die Datei datenschutzrechtlich unauffällig und kann ohne Bedenken per Mail durch die Kette gehen.

**Mehrere Läufe je Gerät sind normal** (Nachprüfung nach Reparatur, zweiter Versuch nach Ladegerät). Die Auswertung behält je Standort+Gerät den **neuesten** Zeitstempel. Die Rohdaten bleiben erhalten, damit nachvollziehbar ist, dass ein Gerät erst defekt war und dann lief.

---

## 4. Die Auswertung

```
# Rollout-Bericht VCI

**Geräte:** 5 · **ok:** 3 · **Warnung:** 1 · **Fehler:** 1 · **Bestandsquote:** 60.0 %
**Zustand:** blockiert — 2 von 5 Geräten nicht bereit

**1 Gerät(e) unter Mindest-Firmware.**

## Standorte
| Standort | ok | Warnung | Fehler | Zustand |
|---|---|---|---|---|
| Filiale Nord | 1 | 0 | 0 | bereit |
| Filiale Mitte | 1 | 1 | 0 | eingeschränkt |
| Filiale West | 0 | 0 | 1 | blockiert |

## Handlungsbedarf nach Prüfung
| Prüfung | Fehler | Warnung | übersprungen |
|---|---|---|---|
| vbat | 1 | 1 | 0 |
| firmware | 1 | 0 | 0 |

## Nicht bereit
- Filiale West / VCI-04: FEHLER (firmware, vbat)
- Filiale Mitte / VCI-05: WARNUNG (vbat, line)
```

Die Tabelle **Handlungsbedarf** ist der eigentliche Wert: sie zeigt nicht, *welches* Gerät kaputt ist, sondern **was der Kette fehlt**. Scheitern 12 von 40 Geräten an `vbat`, ist das kein Hardwareproblem, sondern die Ladegeräte-Disziplin im Betrieb — eine Schulungsmaßnahme, kein Einkauf. Häufig `firmware` → eine Charge ist nicht aktualisiert worden. Häufig `line` → USB-Kabel oder Störquellen am Arbeitsplatz.

Die Reihenfolge ist nach Handlungsbedarf sortiert (Fehler doppelt gewichtet), nicht alphabetisch. `--format csv` gibt alle Datensätze für die Tabellenkalkulation aus.

### Zeitverlauf über mehrere Wochen

Sobald die Sammeldatei mindestens zwei Kalenderwochen umfasst, bekommt der Bericht einen
Abschnitt `## Zeitverlauf`:

```
## Zeitverlauf

| Woche | Geräte | ok | Warnung | Fehler | Quote | ± Vorwoche | Hauptproblem |
|---|---|---|---|---|---|---|---|
| 2026-W45 | 2 | 2 | 0 | 0 | 100.0 % | — | — |
| 2026-W46 | 30 | 27 | 2 | 1 | 90.0 % | -10.0 | vbat (2) |
| 2026-W47 | 61 | 57 | 3 | 1 | 93.4 % | +3.4 | vbat (3) |
```

**Was gezählt wird:** der Zustand der Flotte **am Ende der jeweiligen Woche** — jedes bis
dahin erfasste Gerät mit seinem letzten Lauf bis zu diesem Zeitpunkt. Nicht: was in dieser
Woche geprüft wurde. Der Unterschied ist wesentlich, weil sonst jeder neue Prüftag die
Statistik verzerrt.

**Deshalb auch der Hinweis unter der Tabelle.** Sinkt die Quote, während die Zahl erfasster
Geräte steigt, ist das der normale Verlauf eines Rollouts: die ersten Geräte sind die
vorbereiteten, später kommen die problematischen dazu. Das Werkzeug sagt das ausdrücklich,
statt eine Verschlechterung zu behaupten. Sinkt die Quote dagegen **bei gleichbleibender
Gerätezahl**, steht dort „echter Rückschritt" — das ist der Fall, der vor die nächste
Freigabe gehört.

Die Spalte **Hauptproblem** nennt je Woche die auffälligste Prüfung mit Anzahl. Wandert sie
über die Wochen (`line` → `vbat` → `firmware`), ist die erste Welle behoben und die nächste
sichtbar — der Verlauf zeigt also auch, ob Maßnahmen greifen.

Wochen ohne lesbaren Zeitstempel werden gezählt und unter der Tabelle ausgewiesen, nicht
stillschweigend einsortiert. Bei mehr als 26 Wochen zeigt der Bericht die letzten 26.

### Diagramm für die Betreiber-Präsentation

```bash
rollout chart --in rollout-results.jsonl --out quote.svg \
              --title "VCI-Rollout Werkstattkette Nord" --threshold 80
```

Erzeugt ein SVG (960 × 540, 16:9) mit der Bestandsquote über die Wochen: Fläche und
Linie, Werte an den Punkten, Gerätezahl je Woche unter der Achse und — mit
`--threshold` — die Freigabeschwelle als gestrichelte Linie. Wochen unter der Schwelle
bekommen einen roten Punkt, damit der Blick sofort dort landet, wo es klemmt.

SVG ist bewusst gewählt: skaliert verlustfrei auf Beamer und Druck, lässt sich in
PowerPoint, Keynote, LibreOffice und Inkscape direkt einbetten und braucht keine
Bibliothek auf dem Rechner der Kette. Wer PNG braucht, exportiert es aus dem
Präsentationsprogramm.

Zwei Details, die im ersten Entwurf falsch aussahen und deshalb geändert wurden: die
Beschriftung der Freigabeschwelle liegt in einer Legende statt an der Linie (dort
überlappte sie mit dem Wert der letzten Woche), und Werte an der Schwellenlinie werden
unter den Punkt gesetzt. Ab neun Wochen entfallen die Wert-Labels — die Zahlen stehen
im Bericht, das Bild bleibt lesbar.

### Exit-Codes und Politik

| Code | Bedeutung |
|---|---|
| 0 | Rollout durch: alle Geräte ok, Quote über Schwelle |
| 1 | durch, aber Warnungen oder veraltete Firmware |
| 2 | blockiert: ein Fehler (ohne `--allow-fail`) oder Quote unter `--min-pass` |

`--min-pass 80` heißt: mindestens 80 % der Geräte müssen ohne Befund sein, sonst blockiert der Rollout. `--allow-fail` nimmt bekannte Einzelfälle aus der Blockade-Entscheidung, meldet sie aber weiterhin (Code 1) — ein defektes Gerät soll nie unsichtbar werden.

Damit lässt sich die Freigabe in ein Skript hängen: `rollout report --in results.jsonl --min-pass 80 || echo "Rollout nicht freigeben"`.

---

## 5. Grenzen

- **Berichte sind nicht signiert.** Wer Schreibzugriff auf die Sammeldatei hat, kann einen „ok"-Datensatz erfinden. Für die Steuerung im eigenen Betrieb ausreichend; als Nachweis gegenüber einem Hersteller nicht. Signierte Berichte wären ein eigener Schritt (Geräteschlüssel im VCI).
- **Kein Netzwerkbetrieb.** Kein Server, kein Dashboard, keine Live-Sicht. Wer das braucht, braucht einen Dienst — bewusst nicht Teil dieses Werkzeugs.
- **Geräteidentität ist die Seriennummer aus dem Plan.** Fehlt sie, ist die Zuordnung nur so gut wie die Sorgfalt beim Eintragen. Ein Abgleich mit der vom VCI gemeldeten Seriennummer ist möglich, sobald das Gerät sie über den Vendor-Request liefert — heute meldet es nur Version und Spannung.
- **Schwellwerte des Checkers gelten unverändert.** Ein Gerät, das hier „ok" ist, kann im Feld trotzdem auffallen — der Checker prüft die Messkette, nicht das Fahrzeug.

---

## 6. Stand

**Verifiziert:** 79 Prüfungen in `tests/test_rollout.c` — Parser (eigene Zeilen und echte `vci-check`-Ausgaben), Abweisung von Müll, Dedupe mit neuestem Lauf, Verdichtung, Sortierung nach Handlungsbedarf, numerischer Firmware-Vergleich (`1.10` > `1.4`), alle Politik-Fälle, ISO-Wochen inklusive Jahreswechsel (`2025-12-29` → 2026-W01, `2027-01-01` → 2026-W53), Wochenverlauf mit Rückgang und Erholung, beide Hinweistexte, Markdown/CSV und Puffergrenzen. Zusätzlich Durchläufe über mehrere Standorte mit `run`, `import` und `report`.

**Nicht verifiziert:** kein Lauf gegen echte Geräte über mehrere Standorte; die Zahlen im Beispiel stammen aus simulierten und von Hand gelegten Berichten. Die Schwellen (`--min-pass`) sind Politik, keine Messwerte — sie müssen zur Kette passen, nicht zum Code.
