# Selbsttest in der Werkstatt — was die Anzeige bedeutet

**Für:** Werkstattpersonal am Tablet · **Stand:** 14.11.2026
**Gilt für:** den Bildschirm „Werkstatt VCI“ der App, Stand Selbsttest

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
15 Sekunden Verbindungsaufbau dazu — dort also ruhig bis zu einer halben
Minute warten, bevor „nichts gefunden“ geglaubt wird.

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
  erkannt, antworten aber nicht.
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
