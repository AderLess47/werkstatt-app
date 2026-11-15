# Geräteprüfung — Wartezeilen und Selbsttest-Bericht

**Zweck:** Die Wartezeilen und der Bericht sind geschrieben, übersetzt und
syntaktisch geprüft — aber auf **keinem Gerät** gelaufen. Dieser Plan prüft genau
das nach, in einer Reihenfolge, in der ein Fehler sofort zuzuordnen ist.

**Dauer:** etwa 25 Minuten mit Dongle und Fahrzeug (oder einer Diagnosebank) —
Abschnitt 10 (SFD) braucht zusätzlich ein Fahrzeug, das auf `22 01 74` antwortet.

Was hier **nicht** geprüft wird, weil es dafür einen anderen Aufbau braucht:
Bluetooth-Strecken (zweiter Dongle, Kopplung), Zeitmessung auf die Millisekunde
(Stoppuhr reicht nicht) und ob der Dongle eine 29-Bit-Kennung als Extended Frame
ausgibt (dafür braucht es ein Fahrzeug mit bekannter 29-Bit-Adresse, siehe 10.1).
Die 0x78-Behandlung ist seit Kurzem eingebaut, lässt sich mit diesem Aufbau aber
nur prüfen, wenn ein Steuergerät sie wirklich sendet — die Prüfung ist als Punkt
9 aufgenommen, mit der Alternative, sie weiterhin offen zu lassen.

**Ohne Gerät schon geprüft** (JVM-Tests, siehe
[android-app-modul.md](android-app-modul.md#geprüft--nicht-geprüft)): die
Zeilenbildung der Textwege, das Zusammensetzen und der Aufschub einer
STN-Antwort samt 0x78-Erkennung, die Fristformatierung der Wartezeile, die
Auswertung einer Dienstantwort sowie Bericht und Dateiname. Beim SFD sind es die
Token-Prüfung, die Deutung der Statusbytes samt Rohantwort, die Ziel-Kennungen
mit Bitbreiten-Prüfung und die Schrittfolge mit `10 03` (157 JVM-Tests). Diese
Punkte müssen unten nicht mehr bewiesen werden — nur, dass die App sie auf
diesem Gerät auch anzeigt.

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

## 9. 0x78-Aufschub (optional, nur mit passendem Steuergerät)

1. Ein Steuergerät suchen, das 0x78 sendet — typisch bei Routinen (Dienst `31`)
   oder trägen Kennungen; bei `22 F190` selten. Ohne solches Steuergerät diesen
   Abschnitt **überspringen** und im Abhaken „nicht prüfbar“ eintragen.
2. Den Dienst auf beiden Wegen fahren (Abschnitte 4 und 6).
3. Erwartet auf **Weg A**: die Wartezeile wechselt auf „Steuergerät meldet 0x78
   (Antwort kommt noch) — warte erneut bis 5,0 s, Aufschub 1 von 3“, danach
   kommt die Karte mit der echten Antwort. Auf **Weg B**: die erste Frist bleibt
   bei 3,0 s, danach liest der Kanal bis 5,0 s weiter; kommt nichts, steht
   „nach 0x78 keine Antwort innerhalb von 5000 ms (Dongle-Timeout ATST?)“.
4. **Abweichung wäre:** „abgelehnt (Dienst …, NRC 78: Antwort kommt noch)“ als
   Ergebnis; ein Hänger; oder mehr als drei Aufschübe.
5. **Nicht prüfbar mit diesem Aufbau:** ob `IsotpChannel::recv` die Antwort nach
   0x78 wirklich als neue ISO-TP-Nachricht liefert. Das zeigt nur dieser Versuch
   am Fahrzeug — genau deshalb steht er hier und nicht in der Entwickler-Doku
   als erledigt.

---

## 10. SFD am Fahrzeug

Der SFD-Bildschirm ist der einzige, der ein Steuergerät **verändert** — er
entsperrt und sperrt. Eine Abweichung ist hier mehr als eine Anzeige: ein
falscher Token ist ein Fehlversuch, und nach `0x36` (zu viele Versuche) wartet
das Steuergerät eine Weile. Deshalb in dieser Reihenfolge prüfen: erst die
Adressierung (10.1), dann nur lesend den Status (10.2), erst danach die
Schritte, die senden (10.3).

**Vorher:** Zündung an, und im SFD-Abschnitt auf „USB-Dongle öffnen“ — der Knopf
gehört zum SFD-Abschnitt, unabhängig von „Weg A“.

### 10.1 Adressierung wählen

1. Voreingestellt ist „Motor (01)“. Erwartet unter den Chips die Zeile
   „Adressierung: Motor (01) · 0x7E0 → 0x7E8 (11 Bit)“.
2. Chip „Gateway (19)“ drücken. Erwartet:
   „Adressierung: Gateway (19) · 0x710 → 0x718 (11 Bit)“.
3. Chip „frei“ drücken. Erwartet: zwei Felder „Anfrage-ID“ und „Antwort-ID“ und
   darunter der graue Hinweis „11-Bit bis 0x7FF, darüber 29-Bit (z. B. 18DA10F1).
   Beide Kennungen müssen dieselbe Bitbreite haben.“
4. `7E0` und `7E8` eingeben — der Hinweis bleibt grau. Dann in „Antwort-ID“
   `18DAF110` eingeben. Erwartet: der Hinweis wird **rot** und lautet „Anfrage
   11 Bit, Antwort 29 Bit — ein gemischtes Paar kommt am Bus nicht an“; der Knopf
   „SFD-Status prüfen“ ist gesperrt.
5. **Abweichung wäre:** eine Kennung wird dezimal gelesen (`710` muss als 0x710
   verstanden werden, nicht als 710 = 0x2C6), oder ein gemischtes Paar wird
   angenommen, oder eine 29-Bit-Kennung über `0x1FFFFFFF` wird stillschweigend
   abgeschnitten statt abgelehnt.

**Nicht prüfbar mit diesem Aufbau:** ob der Dongle eine **29-Bit**-Kennung
wirklich als Extended Frame auf den Bus gibt. Das Drahtformat überträgt die
Kennung als Wert ohne Extended-Flag; ob seine Firmware daraus einen 29-Bit-Rahmen
macht, zeigt nur ein Fahrzeug, das auf 29 Bit antwortet. Antwortet es nicht, ist
das **kein** Beweis gegen die App — dann mit der Adresse aus der
Diagnosebeschreibung wiederholen und das Ergebnis hier eintragen („offen“ ist
eine gültige Antwort).

### 10.2 Status lesen und die Rohantwort

1. „SFD-Status prüfen“ drücken. Erwartet **in dieser Reihenfolge**:
   1. Wartezeile „Sende 22 01 74 an Motor (01) · 0x7E0 → 0x7E8 (11 Bit) und warte
      auf den Status — Frist 5,0 s“
   2. Karte mit dem Zustand als Wort: `GESPERRT`, `ENTSPERRT` oder `UNBEKANNT`
   3. darin die Zeile „Rohantwort (N Byte): 620174…“ und darunter „Statusbytes: …“
   4. die Erfolgszeile „Status gelesen (N Byte Antwort)“
2. **Der Kernpunkt dieser Prüfung:** die Zeile „Rohantwort“ muss **immer** da sein
   — auch wenn die Deutung `UNBEKANNT` sagt. Den Hex-Wert wörtlich notieren: er ist
   der einzige Beleg dafür, was das Steuergerät wirklich geantwortet hat.
3. Vergleichen: beginnt der Rohwert mit `62 01 74`? Ein anderer Kopf (etwa `62 01
   75`) heißt, dass die DID beim Fahrzeug eine andere ist — notieren, nicht deuten.
4. **Abweichung wäre:** die Karte fehlt ganz, „Rohantwort“ fehlt, oder es steht
   eine geratene Deutung statt „Zustand unbekannt: Byte 0 ist 0x…“.

### 10.3 Extended Session (10 03)

Der Schalter „Extended Session vorab senden (10 03)“ steht standardmäßig auf
**an**.

1. **Mit Schalter:** „Challenge anfordern“ drücken. Erwartet als Wartezeile
   „Sende Extended Session (10 03) → Challenge (31 01 C0 04) mit Rolle 0x01
   (Extended) — Frist 5,0 s“; danach die Karte „N Byte Challenge“ oder eine
   Fehlerkarte.
2. **Ohne Schalter:** Schalter aus, erneut drücken. Erwartet als Wartezeile nur
   „Sende Challenge (31 01 C0 04) mit Rolle 0x01 (Extended) — Frist 5,0 s“.
3. **Die eigentliche Probe:** lehnt das Steuergerät mit NRC `0x7E` oder `0x7F` ab,
   muss die Fehlerkarte den Schalter nennen — „… in der aktiven Sitzung nicht
   unterstützt — „Extended Session“ (10 03) einschalten und erneut versuchen“.
   Tritt genau das auf, ist der Befund: dieses Steuergerät braucht `10 03`.
4. Beim Token und beim Sperren erscheint dieselbe Vorstufe, sichtbar in der
   Wartezeile: „Sende Extended Session (10 03) → Token senden (31 01 C0 08) mit N
   Byte Token als Multi-Frame — Frist 20,0 s, danach Status lesen“ bzw. „Sende
   Extended Session (10 03) → Sperren (31 01 C0 09) und lese den Status danach —
   Frist 5,0 s“.
5. **Abweichung wäre:** `10 03` wird gesendet, obwohl der Schalter aus ist; oder
   die Wartezeile verschweigt die Session, obwohl sie gesendet wird — dann wartet
   der Techniker auf einen Schritt, der nicht in der Anzeige steht.
6. **Nicht prüfbar ohne passendes Fahrzeug:** ob `10 03` hier überhaupt nötig ist.
   Antwortet das Steuergerät auch ohne, ist der Schalter harmlos — er kostet eine
   Nachricht. Beides ist ein Befund, keiner davon ein Fehler.

---

## 11. Abhaken

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
| 9 | 0x78-Aufschub | „0x78“ in der Wartezeile, danach echte Antwort; kein „NRC 78“ als Ergebnis | | |
| 10 | SFD Adressierung | Chips Gateway/Motor/frei; gemischtes Kennungspaar wird rot abgelehnt | | |
| 11 | SFD Status | Karte mit Rohantwort **auch** bei `UNBEKANNT`; Rohwert beginnt mit `62 01 74` | | |
| 12 | SFD Extended Session | Wartezeile nennt „(10 03)“; NRC 0x7E nennt den Schalter | | |
| 13 | SFD 29 Bit (optional) | Kennung kommt als Extended Frame an — oder als offen notiert | | |

---

## 12. Wenn etwas abweicht

Notieren und mitschicken:

1. welcher Schritt aus Abschnitt 11,
2. der **genaue Text** der Zeile oder Karte (Foto ist erlaubt, wenn es lesbar ist),
3. Tablet-Modell und Android-Version (steht im Kopf des Selbsttest-Berichts),
4. Dongle (VID/PID oder Name),
5. ob der Bericht denselben Schritt nennt wie der Bildschirm.

Ohne diese fünf Angaben ist die Ursache nicht einzukreisen — „geht nicht“ hilft
weder beim Wartezeit-Bug noch beim Dongle.

---

**Stand dieses Plans:** 15.11.2026 (Abschnitt 10 für SFD ergänzt). Er beschreibt
den Code, wie er committet ist, und ist selbst **nicht** am Gerät erprobt — er
ist die Anleitung für diese Prüfung, nicht ihr Ergebnis.
