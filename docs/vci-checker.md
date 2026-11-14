# VCI-Checker — Selbsttest für die Werkstatt

**Stand:** 14.11.2026 · Code: [`j2534/src/checker.c`](../j2534/src/checker.c) · CLI: [`j2534/tools/vci_check.c`](../j2534/tools/vci_check.c)
**Zweck:** die Frage beantworten, die vor jedem Auftrag steht — *ist der Dongle in Ordnung, oder suche ich gleich am falschen Ende?*

---

## 1. Warum das der wirksamste Support-Hebel ist

Aus der Dongle-Matrix im Plan: die Vielfalt der Adapter ist der teuerste Supporttreiber. Ein Drittel der „die App funktioniert nicht"-Fälle ist in Wahrheit ein Kabel, eine leere Batterie, ein zu alter Adapter oder ein Steuergerät, das gar nicht antwortet. Der Checker macht daraus eine Ansage in fünf Sekunden, bevor der Techniker Zeit verliert.

Er prüft **die Messkette bis zum Steuergerät**, nicht das Fahrzeug. Das ist die Abgrenzung, die den Unterschied zwischen „Hinweis" und „Fehldiagnose" ausmacht.

---

## 2. Die sechs Prüfungen

| ID | Prüfung | Grün | Warnung | Fehler | Was die Werkstatt tut |
|---|---|---|---|---|---|
`device` | VCI erreichbar | Gerät antwortet | — | nicht gefunden | Kabel/Anschluss, Dongle neu stecken, Treiber/INF |
`firmware` | Firmware-Stand | ≥ 1.4 | Version nicht lesbar | < 1.4 | Firmware über das Update-Werkzeug einspielen |
`vbat` | Bordspannung | 12,3–14,8 V | 11,8–12,3 V · > 14,8 V | < 11,8 V | Ladegerät anschließen; unter 11,8 V brechen Schreibvorgänge ab |
`bus` | Fahrzeugbus | Antwort ≤ 50 ms | 50–200 ms | keine Antwort | Zündung/Klemme 15, richtiger CAN-Pin, anderes Protokoll |
`isotp` | ISO-TP Mehrfachnachricht | ≥ 8 Byte gelesen | kurze Antwort | Segmentierung fehlgeschlagen | Leitung/VCI tauschen; wird bei fehlendem Bus übersprungen |
`line` | Leitungsqualität | 0 Resync/CRC | 1–5 | > 5 | USB-Kabel, Störquellen (Umrichter, Zündanlage) |

**Wichtig bei `bus`:** „kein Steuergerät antwortet" ist **kein VCI-Fehler**. Der Hinweis nennt Zündung, Klemme 15 und den CAN-Pin — nicht „Dongle defekt". Ein Checker, der das verwechselt, erzeugt Retouren statt Reparaturen.

**Zähler je Lauf, nicht kumulativ:** Resync- und CRC-Zähler der Transportschicht zählen seit Programmstart. Der Checker bildet die Differenz über seinen eigenen Lauf — sonst trägt ein früherer Fehler in jeden folgenden Bericht.

---

## 3. Ausgabe

```
$ vci-check
VCI-Selbsttest  ·  Gerät: sim-vci  ·  Firmware 1.4
──────────────────────────────────────────────────────────────
[OK] Gerät erreichbar              VCI antwortet
[OK] Firmware                       Firmware 1.4
[OK] Bordspannung                   Bordspannung 12.48 V
[OK] Fahrzeugbus                    Antwort in 0 ms
[OK] ISO-TP Mehrfachnachricht       Mehrfachnachricht gelesen (20 Byte)
[OK] Leitungsqualität              0 Resync, 0 CRC-Fehler
──────────────────────────────────────────────────────────────
Ergebnis: OK — 6 von 6 Prüfungen bestanden
```

Bei Warnung oder Fehler steht unter der Zeile ein eingerückter Handlungshinweis. Rückgabewerte: **0 = OK, 1 = Warnung, 2 = Fehler** — damit lässt sich der Test in ein Rollout-Skript für Werkstattketten einhängen.

### JSON-Vertrag für die App

```
$ vci-check --sim --json
{"device":"sim-vci","firmware":"1.4","verdict":"ok","resyncs":0,"crc_errors":0,
 "checks":[
  {"id":"device","status":"ok","value":0,"unit":"","message":"VCI antwortet","hint":""},
  {"id":"vbat","status":"ok","value":12480,"unit":"mV","message":"Bordspannung 12.48 V","hint":""},
  ...
 ]}
```

Das ist die Grenze zur Oberfläche: `id`, `status` (`ok`/`warn`/`fail`/`skip`), `value`, `unit`, `message`, `hint`. Die App rendert daraus die Liste — sie entscheidet nichts nach.

---

## 4. Anbindung an die App

`checker.h` ist reines C ohne Abhängigkeiten außer dem Transport. Drei Wege, in aufsteigendem Aufwand:

| Weg | Aufwand | Anmerkung |
|---|---|---|
iOS: C-Header direkt einbinden | niedrig | Swift kann C-Header via bridging header; `chk_run` blockiert ~1 s, gehört in einen Background-Task — **nur Übergangslösung** ([ADR-001](adr-001-kernsprache.md)) |
Android: JNI-Wrapper (NDK) | mittel | 4 Funktionen (`chk_run`, `chk_to_json`, Statusnamen); **nur Übergangslösung**, bis der Rust-Kern den Checker enthält |
Ziel: im Rust-Kern via UniFFI | mittel | der Checker wird nach `core/` portiert (≈8 PT) und ist dort Teil derselben API wie die Diagnose |

**UI-Vorgaben aus der Werkstattrealität:** Ergebnis in einem Satz oben („VCI in Ordnung" / „Ladegerät anschließen"), Details aufklappbar, Handlungshinweis groß genug für Handschuhe, keine Fehlernummern im Vordergrund. Der Checker läuft automatisch beim Start eines Auftrags und zusätzlich auf Knopfdruck.

---

## 5. Was der Checker bewusst nicht kann

- **Bus-Widerstand (60 Ω)** — braucht eine echte Widerstandsmessung an CAN-H/CAN-L. Die Hardware kann das heute nicht; als Prüfung vorgesehen, sobald das VCI es kann (Aufwand: Hardware, nicht Software).
- **Protokollererkennung** (K-Line, DoIP, CAN FD) — der Checker testet ISO 15765 auf CAN. Ein Fahrzeug mit K-Line meldet „kein Steuergerät", obwohl der Adapter in Ordnung ist. Deshalb nennt der Hinweis ausdrücklich das Protokoll.
- **Firmware-Update** — der Checker stellt fest, dass die Firmware alt ist; das Update ist ein eigener, signierter Vorgang.
- **Lastverhalten** — Dauerlast, Buslast, Spannungseinbrüche unter Schreibvorgängen prüft der Prüfstand, nicht der Checker.

---

## 6. Stand und Grenzen

**Verifiziert:** 34 Prüfungen in `tests/test_checker.c`, alle grün — inklusive der Fälle „11,5 V", „Firmware 1.3", „kein Steuergerät", „Störung auf der Leitung" und „Gerät nicht vorhanden". Der Checker läuft gegen das simulierte Gerät, Text- und JSON-Ausgabe sind geprüft.

**Nicht verifiziert:** alle Schwellwerte sind gesetzt, nicht felderprobt. Die Grenzen (11,8 V, 50/200 ms, 5 CRC-Fehler) sind Erfahrungswerte aus der Werkstattpraxis und müssen nach den ersten 50 Pilotbetrieben nachjustiert werden. Echte Hardware war nicht angeschlossen.

**Gebaut:** der Leser in der App. `VciBericht` (`android/app/src/main/kotlin/de/werkstatt/vci/VciBericht.kt`) liest den JSON-Vertrag aus §3 — Gerät, Firmware, Ergebnis, Resync-/CRC-Zähler und je Prüfung ID, Status, Wert, Einheit, Meldung und Hinweis. Der Bildschirm hat ein Feld, in das die Ausgabe von `vci-check --json` eingefügt wird, und zeigt den Ergebnis-Satz, die Kopfzeile und die Prüfungen mit Handlungshinweis; die App rechnet nichts nach und glättet auch keinen Widerspruch zwischen `verdict` und Einzelprüfung.

**Verifiziert:** 13 JVM-Tests in `VciBerichtTest.kt`, alle grün (alle vier Statuswerte, Wert/Einheit/Hinweis je Prüfung, Escapes inklusive `\u`, abgeschnittener Bericht, fehlende Felder, unbekannte Felder, widersprüchlicher Bericht). **Nicht verifiziert:** die Compose-Oberfläche selbst — sie braucht das Android SDK und ist hier nicht übersetzt worden.

**Gebaut (Teilen):** `BerichtDatei` bildet Dateiname und Inhalt — `vci-bericht-<gerät>-<jjjjmmtt-hhmm>.txt`, oben die Auswertung mit Ergebnis-Satz und Handlungshinweisen, unten der unveränderte JSON-Bericht, damit der Support genau das sieht, was der Checker gesagt hat. `BerichtTeilen` legt die Datei über `MediaStore` in `Downloads/Werkstatt VCI/` ab und öffnet den System-Dialog zum Teilen; ab Android 10 ist das der Weg ohne eigenen `FileProvider` im Manifest. Der Gerätename im Dateinamen wird auf `A–Z`, `a–z`, `0–9`, `-`, `_` und `.` entschärft, weil er vom Dongle kommt.

**Verifiziert:** 6 JVM-Tests in `BerichtDateiTest.kt`, alle grün (Name mit Gerät und Zeitpunkt, entschärfte Sonderzeichen, leerer Gerätename, vollständiger Inhalt, Hinweiszeile nur bei vorhandenem Hinweis, unverändertes JSON am Ende). **Nicht verifiziert:** der Android-Teil — `MediaStore`-Ablage und Teilen-Dialog sind hier nicht übersetzt und auf keinem Gerät gelaufen.
