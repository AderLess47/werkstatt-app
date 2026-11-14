# Selbsttest in der Werkstatt — was die Anzeige bedeutet

**Für:** Werkstattpersonal am Tablet · **Stand:** 14.11.2026
**Gilt für:** den Bildschirm „Werkstatt VCI“ der App, Stand Selbsttest — inklusive
Bericht an den Support (Abschnitt 11)

---

## 1. Wozu der Bildschirm da ist

Der Selbsttest beantwortet **eine** Frage: *Kommt die App bis zum Steuergerät?*
Er ist kein Diagnosebericht und sagt nichts über den Zustand des Fahrzeugs. Ein
grüner Bildschirm heißt nur: der Weg App → Dongle → Bus → Steuergerät steht.

Das läuft in drei Stufen, und die Reihenfolge ist Absicht:

| Stufe | Was geprüft wird |
| --- | --- |
| **Kern** | Die Rechenbibliothek der App lädt und rechnet ISO-TP richtig |
| **Dongle** | USB oder Bluetooth, Stromversorgung, Bordspannung |
| **Steuergerät** | Antwortet überhaupt ein Steuergerät auf dem Bus? |

Fällt eine Stufe aus, ist alles darüber sinnlos. Deshalb immer von oben nach
unten lesen.

---

## 2. Die Zeichen auf dem Bildschirm

Jeder Befund ist eine Karte mit **OK** oder **FEHLER** in der ersten Zeile.
„Prüfe …“ heißt: es läuft noch. Ein Lauf über USB dauert normalerweise unter
zwei Sekunden. Über Bluetooth kommen bis zu 10 Sekunden Suche und bis zu
15 Sekunden Verbindungsaufbau dazu — dort also ruhig bis zu einer halben Minute
warten, bevor „nichts gefunden“ geglaubt wird. Wie lange im Einzelfall gewartet
wird und wann die App aufgibt, steht in Abschnitt 14.

---

## 3. Kern

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **OK** „Kern geladen“ · `libvci_ffi bereit · Adressierung 0x7E0 → 0x7E8` | Die Bibliothek der App ist geladen. Die Adressen sind die üblichen für Motorsteuergeräte (Anfrage 0x7E0, Antwort 0x7E8). | Nichts — das ist der Normalfall. |
| **FEHLER** „Kern nicht ladbar“ mit `UnsatisfiedLinkError` | Die App-Installation ist unvollständig: Die Bibliothek für diesen Gerätetyp fehlt im Paket. Kein Dongle-Problem. | App deinstallieren und neu installieren. Bleibt es dabei: Tablet an die IT, das ist ein Fehler der Installation, nicht der Werkstatt. |
| **FEHLER** „Kern nicht ladbar“ mit anderem Text | Die Bibliothek ist da, lässt sich aber nicht laden (beschädigt, falsche Version). | Wie oben: neu installieren, sonst IT. |
| **OK** „ISO-TP“ · „Anfrage gesendet, Antwort zusammengesetzt“ | Die App zerlegt eine Anfrage in CAN-Rahmen und setzt die Antwort wieder zusammen — gegen ein simuliertes Steuergerät. | Nichts. Dieser Test läuft **ohne** Fahrzeug und ohne Dongle. |
| **FEHLER** „ISO-TP“ · „unerwartete Antwort: …“ | Die Rechenbibliothek liefert ein falsches Ergebnis. Das ist ein Softwarefehler, kein Werkstattproblem. | Nicht am Fahrzeug weitermachen. App-Version notieren und melden. |

---

## 4. Dongle über USB

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **OK** „Dongle“ · `VID 0403:PID 6001 · <Name>` (Beispiel, die Zahlen sind je Dongle anders) | Der Dongle ist erkannt und die App darf mit ihm sprechen. | Weiter mit Bordspannung. |
| **FEHLER** „kein USB-Dongle gefunden — Kabel, USB-Host und Stromversorgung prüfen“ | Kein Gerät sichtbar. | 1. Kabel am Dongle und am Tablet fest andrücken, anderes Kabel probieren. 2. Anderen USB-Anschluss am Tablet nehmen. 3. Prüfen, ob das Tablet USB-Geräte überhaupt zulässt. 4. Dongle an einem PC gegenprüfen. |
| **FEHLER** „… · Zugriff verweigert“ | Android hat die Freigabe nicht bekommen, oder der Dialog wurde weggetippt. | Erneut „USB prüfen“ drücken und im Dialog **Zulassen** wählen. Kommt kein Dialog: App in den Einstellungen einmal stoppen und neu starten. |
| **FEHLER** „ließ sich nicht öffnen — von einer anderen App belegt?“ | Eine andere App (oder ein alter Absturz) hält den Dongle. | Alle Diagnose-Apps schließen, Kabel kurz abziehen und wieder stecken, erneut prüfen. Tablet neu starten, wenn es bleibt. |

**Wichtig:** Der USB-Dialog von Android erscheint nur beim ersten Mal pro Dongle.
Wenn er nicht kommt und der Befund „Zugriff verweigert“ lautet, ist die Freigabe
früher verweigert worden — dann hilft nur Zurücksetzen in den App-Einstellungen
oder Neu-Anstecken.

---

## 5. Dongle über Bluetooth

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **FEHLER** „kein Bluetooth-Adapter“ | Das Gerät hat kein Bluetooth. | USB-Weg nehmen. |
| **FEHLER** „aus — in den Einstellungen einschalten“ | Bluetooth ist ausgeschaltet. | Einschalten und erneut prüfen. |
| **FEHLER** „ohne Berechtigung kein Scan möglich“ | Die Freigabe für Bluetooth wurde abgelehnt. | Erneut „Bluetooth prüfen“ drücken und im Systemdialog **Zulassen** wählen. Bei dauerhafter Ablehnung: App-Berechtigungen in den Einstellungen zurücksetzen. |
| **OK** „Bluetooth“ · `<Name> · <Adresse>` | Der Dongle ist gefunden und verbunden; die App bekommt Daten. | Weiter mit Bordspannung. |
| **FEHLER** „kein Dongle gefunden — eingeschaltet, in Reichweite, bereits verbunden?“ | Innerhalb von 10 Sekunden hat sich kein Dongle gemeldet. | 1. Dongle leuchtet? Batterie/Klemme 15? 2. Abstand unter 2 Metern, kein Metall dazwischen. 3. Bluetooth am Tablet aus/an. 4. Ist der Dongle noch mit dem Handy des Kollegen gekoppelt? Dann dort trennen. |
| **FEHLER** „Verbindung oder Dienst-Suche fehlgeschlagen“ | Gefunden, aber der Aufbau ist gescheitert. | Dongle stromlos machen (abziehen), 10 Sekunden warten, erneut. Häufig bei Dongles, die schon an einem anderen Gerät hängen. |

Nur Dongles, die den in der App hinterlegten Bluetooth-Dienst anbieten, werden
überhaupt angezeigt. Fremdgeräte in der Werkstatt (Kopfhörer, Sensoren) tauchen
deshalb nicht auf — wenn also „nichts gefunden“ kommt, obwohl der Dongle blinkt,
ist es sehr wahrscheinlich der falsche Dongle-Typ. Siehe Abschnitt 8.

---

## 6. Bordspannung

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **OK** „12,4 V am Diagnosestecker“ (9–16 V) | Der Dongle wird versorgt und misst die Spannung selbst. Das ist die Spannung **am Stecker**, nicht der Zustand der Batterie. | Nichts. |
| **FEHLER** „Dongle meldet keine Spannung — Klemme 15/30 prüfen“ | Der Dongle kann nicht messen oder bekommt keinen Strom über den Stecker. | 1. Stecker ganz eingesteckt? 2. Zündung an. 3. Sicherung des Diagnosesteckers prüfen. 4. Dongle an einem anderen Fahrzeug testen. |
| **FEHLER** „9,1 V — außerhalb des erwarteten Bereichs (9–16 V)“ (unter 9 V) | Die Bordspannung ist zu niedrig — meist entladene Batterie oder ein Fremdverbraucher. | Batterie laden, Ladegerät anschließen. **Keine Codierung und kein Flashen** in diesem Zustand. Vorher messen: ein Multimeter zeigt, ob es die Batterie oder der Dongle ist. |
| **FEHLER** „… außerhalb des erwarteten Bereichs (9–16 V)“ (über 16 V) | Zu hoch — oft Ladegerät im Schnellmodus oder ein defekter Regler. | Ladegerät auf normales Laden stellen. Bei über 15 V im Stand: Fahrzeug nicht weiter beschreiben, Regler prüfen lassen. |
| **FEHLER** „nicht lesbar: …“ | Die Anfrage kam nicht als Antwort zurück. | Erneut prüfen. Bleibt es: USB- oder Bluetooth-Verbindung ist instabil — Abschnitt 4 bzw. 5. |

---

## 7. Steuergerät

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **OK** „antwortet auf TesterPresent“ | Ein Steuergerät antwortet auf die einfachste aller Anfragen (0x3E 0x00). Der Weg bis ins Steuergerät steht. | Nichts — ab hier kann die eigentliche Diagnose beginnen. |
| **FEHLER** „keine Antwort — Zündung an, Bus erreichbar?“ | Kein Steuergerät hat geantwortet. | 1. Zündung an (Klemme 15). 2. Richtiges Fahrzeug/Kabel? 3. Anderes Steuergerät über die normale Diagnose ansprechen. 4. Antwortet auch das nicht, liegt es am Fahrzeug oder am Dongle — nicht an der App. |
| **FEHLER** „unerwartete Antwort: …“ | Es kam eine Antwort, aber nicht die erwartete. | Meist ein anderes Steuergerät auf dem Bus oder ein zweiter Tester im Netz. Zweiten Tester trennen, erneut prüfen. Tritt es wiederholt auf: notieren, welche Bytes angezeigt werden, und melden. |

**Ein roter „Steuergerät“-Befund ohne Fahrzeug ist normal** — auf dem Werkstatt-Tisch
antwortet niemand. Bordspannung und Verbindung lassen sich trotzdem prüfen; genau
dafür ist der Bildschirm gebaut.

---

## 8. Was die App nicht prüft

Ehrlich benannt, damit niemand mehr hineinliest, als drin ist:

- **Keine Fehlercodes**, keine Messwerte, keine Stellglieddiagnose.
- **Keine Codierung, kein Flashen** — der Selbsttest schreibt nichts ins Fahrzeug.
- **Keine Aussage über den Dongle-Typ**: Die App spricht ein eigenes, dokumentiertes
  Drahtformat. ELM327-Klone und reine Text-Dongles funktionieren bewusst nicht
  (siehe [Dongle-Matrix](vci-dongle-matrix.md)) — sie werden beim USB-Weg u. U.
  erkannt, antworten aber nicht. Klasse-1-Geräte mit STN-Firmware (OBDLink EX/SX
  am FTDI-Kabel) haben einen **eigenen** Weg — Abschnitt 12.
- **Kein Ersatz für Multimeter oder Oszilloskop**: Die Spannung ist ein
  Näherungswert des Dongles, keine belastbare Messung.
- **Keine Diagnose des Busses**: Ein verdrehtes CAN-Paar, ein Kurzschluss oder ein
  falscher Abschlusswiderstand sehen für die App genauso aus wie „keine Antwort“.

---

## 9. Sicherheit

1. **Nie unter 12 V codieren oder flashen.** Bei „Bordspannung FEHLER“ zuerst laden.
2. **Zündung an, Motor aus** für den Selbsttest — der Stecker muss Strom haben.
3. **Dongle nicht während einer laufenden Diagnose abziehen.**
4. **Bei „Kern nicht ladbar“ nichts am Fahrzeug versuchen** — die App rechnet dann nicht.
5. Diagnose am fahrenden Fahrzeug nur, wenn eine zweite Person das Tablet hält.

---

## 10. Wann tauschen, wann melden

**Dongle tauschen**, wenn drei Punkte zutreffen:

1. Der Fehler ist an **zwei verschiedenen Fahrzeugen** reproduzierbar.
2. Ein **anderes Kabel** und ein **anderer USB-Anschluss** ändern nichts.
3. Der Dongle funktioniert an einem **PC** ebenfalls nicht.

**App melden** (nicht Dongle), wenn:

- „Kern nicht ladbar“ oder „ISO-TP: unerwartete Antwort“ erscheint — das ist Software.
- Alle anderen Befunde grün sind, aber die Antwort des Steuergeräts wiederholt
  falsche Bytes zeigt.

In beiden Fällen notieren: Datum, Tablet-Modell, Android-Version, Dongle (VID/PID
bzw. Bluetooth-Name), Fahrzeug, welcher Befund rot war und der **genaue Text** der
Karte. Ohne diese fünf Angaben ist jede Ferndiagnose Raten.

Das meiste davon steht schon im **Selbsttest-Bericht** — wie Sie ihn erzeugen und
losschicken, steht in Abschnitt 11.

---

## 11. Den Bericht an den Support schicken

Es gibt **zwei** Berichte. Sie kommen aus verschiedenen Programmen und werden
getrennt verschickt — nicht vermischt:

| Bericht | Woher | Wann |
| --- | --- | --- |
| **Selbsttest-Bericht** | diese App, ein Knopfdruck | wenn der Bildschirm etwas Rotes zeigt |
| **Checker-Bericht** (`vci-check --json`) | Rechner in der Werkstatt | wenn zusätzlich der VCI-Checker gelaufen ist |

### Selbsttest-Bericht — drei Schritte

1. **Prüfen lassen:** „Kern erneut prüfen“, dann „USB prüfen“ oder
   „Bluetooth prüfen“. Beide Ergebnisse stehen danach auf dem Bildschirm.
2. **Teilen:** Knopf **„Selbsttest-Bericht teilen“** drücken (direkt unter den
   Prüf-Knöpfen). Die App legt die Datei ab und öffnet den Teilen-Dialog.
3. **Schicken:** Mail oder Messenger wählen, zwei Sätze dazuschreiben, absenden.

Die Datei heißt `vci-selbsttest-<Gerät>-<Datum>-<Uhrzeit>.txt` und liegt
zusätzlich unter **Downloads → Werkstatt VCI**. Wer den Dialog wegtippt, findet
sie dort und kann sie von Hand anhängen.

### Checker-Bericht — wenn er schon vorliegt

Auf dem Rechner `vci-check --json` laufen lassen, die Ausgabe kopieren, in der App
in das Feld **„JSON-Bericht“** einfügen, **„Bericht auswerten“** drücken und dann
**„Bericht als Datei teilen“**. Die Datei enthält oben die Auswertung in Klartext
und unten den unveränderten JSON-Bericht.

### In die Nachricht gehört

- Fahrzeug (Kennzeichen oder Auftragsnummer) und was der Kunde bemerkt hat
- was Sie schon versucht haben: Kabel, USB-Anschluss, anderes Fahrzeug
- der Bericht als **Dateianhang** — kein Foto vom Bildschirm: im Bericht steht
  der genaue Text jeder Prüfung, ein Foto ist unscharf und langsamer zu lesen

Wohin: an die Support-Adresse, die im Werkstattaushang steht.

**Noch nicht erprobt:** Der Teilen-Weg ist gebaut, aber noch auf keinem Gerät
gelaufen. Kommt der Dialog nicht oder lässt sich die Datei nicht anhängen, liegt
sie trotzdem unter Downloads → Werkstatt VCI — von dort per Kabel oder
Dateien-App weiterschicken.

---

## 12. OBDLink am Kabel (Klasse 1)

OBDLink EX/SX hängen hinter einem FTDI-Wandler und sprechen **Textbefehle** — ein
eigener Weg neben dem rohen USB-Weg. Der Dongle macht dabei ISO-TP selbst,
deshalb gibt es hier zwei Schritte statt einer Prüfung.

| Schritt | Knopf | Was passiert |
| --- | --- | --- |
| 1 | **OBDLink öffnen** | sucht ein FTDI-Gerät, fragt die USB-Freigabe ab, öffnet mit 115.200 Baud und fragt `STI` — erst wenn dort `STN…` steht, wird der Dongle angenommen |
| 2 | **Dienst 22 F190 lesen** | liest die Fahrgestellnummer (ein lesender Dienst) und zeigt Größe, Hex und den druckbaren Text |

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **OK** „OBDLink“ · `STN2256 v5.9.1 · VID 0403:PID 6015` (Beispiel) | STN-Firmware bestätigt, Port offen. | Weiter mit Schritt 2. |
| **FEHLER** „kein FTDI-Dongle gefunden“ | Kein Gerät mit FTDI-Chip sichtbar. | Kabel prüfen, anderen USB-Anschluss nehmen, Dongle an einem PC gegenprüfen. |
| **FEHLER** „… kein STN-Gerät“ | Es antwortet etwas, aber nicht mit `STN…` — meist ein ELM327-Clone. | Nicht weiterarbeiten; Gerät aus der [Dongle-Matrix](vci-dongle-matrix.md) nehmen. |
| **FEHLER** „Dongle antwortet nicht“ | Der Port ist offen, aber die Einstellbefehle werden nicht bestätigt. | Zündung an, Dongle ab- und wieder anstecken, neu öffnen. |
| **OK** „Dienst 22 F190“ · `15 Byte · 62 F1 90 … · Text: …` | Das Steuergerät hat geantwortet. | Nichts — das ist der Nachweis, dass der Weg bis ins Steuergerät steht. |
| **FEHLER** „abgelehnt (Dienst 22, NRC 31 …)“ | Das Steuergerät kennt diese Kennung nicht. | Anderes Fahrzeug probieren. Der Dongle ist in Ordnung. |
| **FEHLER** „… NRC 22: Bedingungen nicht erfüllt — Zündung an?“ | Meist fehlt Klemme 15. | Zündung an, erneut lesen. |
| **FEHLER** „Steuergerät antwortet nicht“ | Kein Gerät auf dem Bus. | Wie Abschnitt 7 — Zündung, Pin, anderes Steuergerät. |

**Grenzen dieses Wegs:** nur lesen. Kein Schreiben, kein Codieren, kein Flashen,
und langsam: Textbefehle brauchen Sekunden, große Blöcke länger. Der rohe
USB-Weg (Abschnitt 4) bleibt für Dongles, die unser eigenes Drahtformat sprechen —
die beiden Knöpfe schalten sich nicht gegenseitig um.

---

## 13. Eigenes Drahtformat (Weg A)

Für Dongles, die unser eigenes Rahmenformat sprechen (die Geräte aus Abschnitt 4).
Hier rechnet **die App** ISO-TP: der Dongle sieht nur CAN-Rahmen.

| Schritt | Knopf | Was passiert |
| --- | --- | --- |
| 1 | **USB-Dongle öffnen** | sucht ein USB-Gerät mit Bulk-Endpunkten, fragt die Freigabe ab, öffnet es und bestätigt das Rahmenformat |
| 2 | **Dienst 22 F190 lesen** | schickt die Kennung durch den Kern (`IsoTp`) und zeigt Größe, Hex und Text der Antwort |

| Anzeige | Bedeutung | Was tun |
| --- | --- | --- |
| **OK** „USB-Dongle“ · `VID 0403:PID 6001 · <Name>` (Beispiel) | Gerät offen und angenommen. | Weiter mit Schritt 2. |
| **FEHLER** „kein USB-Dongle gefunden …“ | Kein passendes Gerät sichtbar. | Wie Abschnitt 4: Kabel, Anschluss, PC-Gegenprobe. |
| **FEHLER** „Zugriff verweigert“ | Die Freigabe fehlt. | Erneut öffnen und **Zulassen** wählen; sonst App neu starten. |
| **FEHLER** „ließ sich nicht öffnen — von einer anderen App belegt?“ | Ein anderes Programm hält das Gerät. | Diagnose-Apps schließen, Kabel ab/an, erneut. |
| **OK** „Dienst 22 F190“ · `15 Byte · 62 F1 90 … · Text: …` | Der Weg bis ins Steuergerät steht, auf **diesem** Weg. | Nichts. |
| **FEHLER** „abgelehnt (Dienst 22, NRC 31 …)“ | Das Steuergerät kennt die Kennung nicht. | Anderes Fahrzeug; der Dongle ist in Ordnung. |
| **FEHLER** „keine Antwort — Zündung an, Bus erreichbar?“ | Kein Gerät auf dem Bus oder Abbruch der Segmentierung. | Wie Abschnitt 7: Zündung, Pin, anderes Steuergerät. |

**Abgrenzung zu „USB prüfen“ (Abschnitt 4):** Der Knopf weiter oben macht den
Schnelltest — Gerät finden, Bordspannung, TesterPresent — und sein Ergebnis geht
in den Selbsttest-Bericht. Dieser Abschnitt liest zusätzlich einen Dienst.

**Vergleich der beiden Wege:** Abschnitt 12 und 13 lesen dieselbe Kennung. Kommt
beim selben Fahrzeug einmal eine Antwort und einmal „keine Antwort“, liegt es am
Weg (Adressierung, Header oder Segmentierung), nicht am Steuergerät — dann mit
beiden Wegen erneut prüfen und beide Berichte mitschicken.

---

## 14. Wie lange „Läuft …“ stehen darf

Solange „Läuft …“ auf dem Bildschirm steht, arbeitet die App. Die Knöpfe sind
dann absichtlich gesperrt — bitte **nicht** mehrfach drücken, nicht abziehen und
das Tablet nicht sperren. Warten Sie, bis der Befund erscheint; er kommt immer,
spätestens wenn die App aufgibt.

| Vorgang | Normal | Spätestens |
| --- | --- | --- |
| USB-Gerät öffnen (Abschnitte 4 und 13) | unter 1 Sekunde | nach der Freigabe im Dialog sofort; kommt der Dialog nicht, nach etwa einer halben Minute |
| Schnelltest: Bordspannung und Steuergerät (Abschnitt 7) | unter 1 Sekunde | nach etwa 1 Sekunde |
| Dienst lesen, roher USB-Weg (Abschnitt 13) | unter 1 Sekunde | nach etwa 5 Sekunden |
| Dienst lesen, OBDLink am Kabel (Abschnitt 12) | 1–2 Sekunden | nach etwa 3 Sekunden |
| OBDLink öffnen (Abschnitt 12) | 1–2 Sekunden | nach etwa 15 Sekunden |

**Wird die Grenze ohne Antwort erreicht**, steht dort ein Fehler — meist „keine
Antwort“ oder „Dongle antwortet nicht“. Das ist kein Hänger: Die App hat gewartet
und gibt auf, statt den Bildschirm zu blockieren. Dann gilt die Spalte „Was tun“
im jeweiligen Abschnitt: Zündung, Kabel, Stecker, Gegenprobe am PC.

**Warum die Wege unterschiedlich lange warten:** Der rohe USB-Weg setzt die
Antwort aus mehreren Rahmen zusammen und braucht dafür mehr Luft. Beim
OBDLink-Weg kommt die fertige Antwort vom Dongle, deshalb bricht die App dort
früher ab. Die genauen Grenzen samt Herleitung stehen in der Entwickler-Doku
([android-app-modul.md](android-app-modul.md), Abschnitt „Wartezeiten“) — für den
Werkstattalltag reicht die Tabelle oben.
