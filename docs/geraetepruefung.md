# Geräteprüfung — Wartezeilen und Selbsttest-Bericht

**Zweck:** Die Wartezeilen und der Bericht sind geschrieben, übersetzt und
syntaktisch geprüft — aber auf **keinem Gerät** gelaufen. Dieser Plan prüft genau
das nach, in einer Reihenfolge, in der ein Fehler sofort zuzuordnen ist.

**Dauer:** etwa 15 Minuten mit Dongle und Fahrzeug (oder einer Diagnosebank).

Was hier **nicht** geprüft wird, weil es dafür einen anderen Aufbau braucht:
Bluetooth-Strecken (zweiter Dongle, Kopplung), Zeitmessung auf die Millisekunde
(Stoppuhr reicht nicht) und die 0x78-Behandlung (nicht implementiert, siehe
[android-app-modul.md](android-app-modul.md), Abschnitt „Wartezeiten“).

---

## 0. Vorher bereitlegen

| Was | Warum |
| --- | --- |
| APK mit `libvci_ffi.so` für das Geräte-ABI | ohne die Bibliothek bleibt der Kern rot, alles andere ist dann nicht prüfbar |
| Dongle für Weg A (eigenes Drahtformat) | Abschnitte 3 und 4 |
| OBDLink EX/SX am FTDI-Kabel | Abschnitte 5 und 6 |
| Fahrzeug mit Zündung, oder Diagnosebank | sonst bleibt „Steuergerät“ ehrlich rot |
| Zettel oder dieses Dokument zum Abhaken | die Spalte „gesehen“ ist der eigentliche Ertrag |

Ein roter „Steuergerät“-Befund **ohne** Fahrzeug ist kein Fehler — auf dem Tisch
antwortet niemand.

---

## 1. Kern

1. App starten. Erwartet: kurz „Prüfe Kern — Bibliothek laden und ISO-TP gegen den
   Simulator …“, dann die Karten „Kern geladen“ und „ISO-TP“.
2. **Abweichung wäre:** die Zeile bleibt länger als zwei Sekunden stehen, oder sie
   erscheint gar nicht (dann prüft die App ohne Anzeige).

---

## 2. Dongle-Schnelltest (USB)

1. „USB prüfen“ drücken. Erwartet: „Suche USB-Dongle, prüfe Freigabe, Bordspannung
   und TesterPresent …“, danach Bordspannung und Steuergerät als Karten.
2. Beim ersten Mal je Dongle kommt der Android-Dialog zur Freigabe. Erwartet: die
   Wartezeile bleibt stehen, solange der Dialog offen ist.
3. **Abweichung wäre:** der Dialog erscheint, die Wartezeile aber nicht; oder die
   Wartezeile verschwindet, während der Dialog noch offen ist.

---

## 3. Weg A — öffnen

1. Im Abschnitt „Eigenes Drahtformat (Weg A)“ auf „USB-Dongle öffnen“ drücken.
   Erwartet: „Suche USB-Dongle und frage die Freigabe ab — der Dialog darf bis 30 s
   offen bleiben“.
2. Danach: Karten „USB-Dongle“ und „Drahtformat“.
3. **Abweichung wäre:** der Text wird abgeschnitten (schmale Bildschirme), oder die
   Karten erscheinen, ohne dass die Zeile je sichtbar war.

---

## 4. Weg A — Dienst lesen

1. „Dienst 22 F190 lesen“ drücken. Erwartet **in dieser Reihenfolge**:
   1. „Sende Anfrage 22 F190 …“
   2. „Warte auf die Antwort auf 22 F190 — Frist 1,5 s je Rahmen, bei 20 Byte bis
      4,5 s“
   3. Karte „Dienst 22 F190“ mit Größe, Hex und Text — oder
      „beim Warten auf die Antwort abgebrochen: …“
2. **Abweichung wäre:** Schritt 2 erscheint nie (dann wartet die App ohne Ansage),
   oder die Reihenfolge ist vertauscht.

---

## 5. Klasse 1 — öffnen

1. Im Abschnitt „OBDLink (Klasse 1)“ auf „OBDLink öffnen“ drücken. Erwartet:
   „Suche FTDI-Gerät, frage die Freigabe ab, prüfe die STI-Kennung …“, dann
   „Sende ATZ und die Einstellungen — Frist 2,0 s je Befehl“, dann die Karten
   „OBDLink“ und „Dongle eingestellt“.
2. **Abweichung wäre:** der zweite Text fehlt (die Einstellbefehle laufen dann
   ohne Ansage), oder „Dongle antwortet nicht: …“ erscheint ohne vorherige Zeile.

---

## 6. Klasse 1 — Dienst lesen

1. „Dienst 22 F190 lesen“ drücken. Erwartet: „Sende 22 F190 und warte auf die
   Antwort — Frist 3,0 s für die ganze Antwort (der Dongle sammelt sie selbst)“.
2. Danach die Karte mit der Antwort oder „beim Warten auf die Antwort
   abgebrochen: …“.
3. **Abweichung wäre:** die Zeile nennt eine andere Frist als die Anleitung
   (dort: erstes Lesen etwa 5 s, danach etwa 3 s).

---

## 7. Der Bericht

1. Auf „Selbsttest-Bericht teilen“ drücken, Datei öffnen (Ablage steht im Dialog)
   oder über **Downloads → Werkstatt VCI** holen.
2. Erwartet im Kopf: Zeitpunkt, Gerät, dann „Kern“, „Dongle“ und die Zeile
   „Zuletzt gewartet auf“ mit **genau dem Schritt**, der zuletzt auf dem
   Bildschirm stand.
3. Erwartet je nach Verlauf:
   - Nach Abschnitt 6: der Text aus „Sende 22 F190 und warte auf die Antwort …“
   - Nur nach Abschnitt 1: „nicht erfasst“ (die Kern-Prüfung kündigt keinen
     Schritt an)
4. **Abweichung wäre:** „nicht erfasst“, obwohl Abschnitt 4 oder 6 gelaufen ist;
   oder ein Schritt, der älter ist als der zuletzt angezeigte.

---

## 8. Fehlerfälle — die interessanten

Diese drei erzeugen die Wartezeilen im Ernstfall. Jeder Fall: Ursache herstellen,
Knopf drücken, Text der Karte notieren.

| Fall | So herstellen | Erwartete Karte |
| --- | --- | --- |
| keine Antwort vom Steuergerät | Zündung aus, dann „Dienst 22 F190 lesen“ | „beim Warten auf die Antwort abgebrochen: …“ — der Text nach dem Doppelpunkt kommt aus dem Kern (erwartet „Zeitüberschreitung“); wenn dort etwas anderes steht, ist genau das eine Notiz wert |
| Dongle während des Lesens weg | Dongle abziehen, während „Warte auf die Antwort …“ steht | Abbruch mit Transport- oder Zeitfehler, **kein** Hänger |
| falscher Text-Dongle (ELM-Clone) | ELM327-Clone anstecken, „OBDLink öffnen“ | „… kein STN-Gerät — nur Geräte mit STN-Firmware werden angesprochen“ |

**Wichtig:** Ein Hänger (die Zeile bleibt und es kommt nie ein Befund) ist der
einzige echte Blocker in diesem Plan — dann Tablet nicht bedienen, sondern
notieren, wie lange die Zeile stand, und die App schließen.

---

## 9. Abhaken

| # | Schritt | erwartet | gesehen | Abweichung |
| --- | --- | --- | --- | --- |
| 1 | Kern | Zeile < 2 s, dann zwei Karten | | |
| 2 | USB-Schnelltest | Zeile während Freigabedialog | | |
| 3 | Weg A öffnen | Zeile mit „bis 30 s“ | | |
| 4 | Weg A lesen | drei Schritte in Reihenfolge | | |
| 5 | Klasse 1 öffnen | zwei Zeilen (Suche, ATZ) | | |
| 6 | Klasse 1 lesen | Zeile mit „3,0 s“ | | |
| 7 | Bericht | „Zuletzt gewartet auf“ = letzter Schritt | | |
| 8 | Fehlerfälle | Karten statt Hänger | | |

---

## 10. Wenn etwas abweicht

Notieren und mitschicken:

1. welcher Schritt aus Abschnitt 9,
2. der **genaue Text** der Zeile oder Karte (Foto ist erlaubt, wenn es lesbar ist),
3. Tablet-Modell und Android-Version (steht im Kopf des Selbsttest-Berichts),
4. Dongle (VID/PID oder Name),
5. ob der Bericht denselben Schritt nennt wie der Bildschirm.

Ohne diese fünf Angaben ist die Ursache nicht einzukreisen — „geht nicht“ hilft
weder beim Wartezeit-Bug noch beim Dongle.

---

**Stand dieses Plans:** 14.11.2026. Er beschreibt den Code, wie er committet ist,
und ist selbst **nicht** am Gerät erprobt — er ist die Anleitung für diese
Prüfung, nicht ihr Ergebnis.
