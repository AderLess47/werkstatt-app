# Wartezeiten am Prüfplatz — Aushang

**Stand: 14.11.2026** · Gilt für den Bildschirm „Werkstatt VCI“, Selbsttest

Solange eine **Wartezeile** steht („Suche …“, „Sende …“, „Warte auf …“), arbeitet
die App. Sie sagt dabei, worauf sie wartet. Die Knöpfe sind absichtlich gesperrt.

---

## Wie lange es dauern darf

| Vorgang | normal | spätestens |
| --- | --- | --- |
| USB-Gerät öffnen, Freigabe | sofort nach „Zulassen“ | Dialog offen: bis 30 Sekunden |
| Schnelltest: Spannung, Steuergerät | unter 1 Sekunde | nach etwa 1 Sekunde |
| Dienst lesen — roher USB-Weg | unter 1 Sekunde | nach etwa 5 Sekunden |
| Dienst lesen — OBDLink am Kabel | 1–2 Sekunden | 1. Mal etwa 5 s, danach etwa 3 s |
| Bluetooth: suchen und verbinden | 2–5 Sekunden | nach etwa 25 Sekunden |

Die **Kern-Prüfung** beim Start hat kein Zeitlimit — sie rechnet im Telefon und
ist normalerweise in unter einer Sekunde fertig. Steht dort länger „Prüfe Kern …“,
ist etwas mit der App-Installation nicht in Ordnung (siehe Regel 3).

---

## Vier Regeln

1. **Nicht mehrfach drücken** und **nicht abziehen**, solange die Zeile steht.
   Jeder zweite Druck macht die Wartezeit länger, nicht kürzer.
2. **Kommt ein Fehler**, gilt diese Reihenfolge: Zündung an → Stecker und Kabel
   prüfen → anderes Fahrzeug → Gegenprobe am PC.
3. **Nie ein Befund, immer nur die Zeile?** Das ist ein Hänger und der einzige
   echte Fall für den Support: notieren, wie lange die Zeile stand, App schließen,
   Selbsttest-Bericht schicken.
4. **Roter „Steuergerät“-Befund ohne Fahrzeug ist normal** — auf dem Tisch
   antwortet niemand. Bordspannung und Verbindung sind trotzdem geprüft.

---

## Wenn zwei Wege verschieden antworten

Abschnitt „OBDLink (Klasse 1)“ und „Eigenes Drahtformat (Weg A)“ lesen dieselbe
Kennung. Antwortet einer und der andere nicht, liegt es am **Weg**, nicht am
Steuergerät: mit beiden Wegen wiederholen und **beide Berichte** mitschicken.

---

*Aushang zur Werkstatt-Anleitung §14. Ausführlich: `docs/selbsttest-anleitung.md` ·
Prüfplan für die Geräteprüfung: `docs/geraetepruefung.md` · Die Zeiten sind
Obergrenzen, an denen die App aufgibt — keine Messwerte vom Fahrzeug.*
