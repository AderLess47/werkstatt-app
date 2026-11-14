# VCI-/Dongle-Matrix — Kompatibilität für die Werkstatt-App

**Stand:** 14.11.2026 · Ergänzung zu [`werkstatt-app-plan.md`](werkstatt-app-plan.md) §5
**Zweck:** Festlegen, welche Fahrzeug-Schnittstellen (VCIs/Dongles) die App unterstützt, in welcher Tiefe — und welche bewusst nicht.

---

## 0. Lesart dieser Matrix

| Spalte | Bedeutung |
|---|---|
**Klasse** | 1 = generisch OBD · 2 = Profi-Passthru (J2534) · 3 = DoIP/Ethernet · 4 = OEM-Werkstatttool |
**Host** | Wie das Gerät mit Tablet/Phone spricht: BLE, WLAN, USB, IP-Gateway |
**Tiefe** | L = Lesen/Diagnose · S = Service-Rückstellung · C = Codierung/Adaption · F = Flashen |
**App-Status** | `geplant` · `zu zertifizieren` · `nur über Gateway` · `eingestellt` (Gerät nicht mehr beschaffbar, Support nur für den Bestand) · `nicht unterstützt` |

> **Grundregel:** SAE J2534-1 definiert in §7 eine **32-Bit-Windows-DLL**. Ein J2534-Gerät ist damit nie „einfach so" am Tablet. Entweder liefert der Hersteller zusätzlich Android-/iOS-Builds (Weg 1), oder ein Windows-Gateway im Werkstatt-LAN vermittelt (Weg 2). Diese Spalte steht deshalb in jeder Zeile.

---

## 1. Klasse 1 — generische OBD-Adapter (BLE, WLAN, USB)

| Gerät | Chipsatz | Host | Busse | Tiefe | App-Status | Anmerkung |
|---|---|---|---|---|---|---|
OBDLink MX+ | STN2256 (früher STN2255) | BLE | CAN, ISO-TP, K-Line, J1850 | L, S (eingeschränkt) | zu zertifizieren (STN) | Bestes generisches Gerät; STN-Befehlssatz statt ELM-Quirk; für Service-Rückstellung brauchbar, **nicht** für Flashen |
OBDLink CX | STN2310 | BLE | CAN, ISO-TP | L, S (eingeschränkt) | zu zertifizieren (STN) | iOS-freundlich (BLE), kompakt |
OBDLink EX | STN2232 (früher STN2230/2231) | USB (FTDI) | CAN, ISO-TP, K-Line | L, S | zu zertifizieren (STN, Kabel) | Aktives USB-Modell; sitzt hinter einem FTDI-Wandler, nicht an einem rohen Bulk-Endpunkt. Der Weg ist in der App gebaut, am Gerät aber ungeprüft. Auf iPad nicht nutzbar |
OBDLink SX | STN1130 | USB (FTDI) | CAN, ISO-TP, K-Line | L, S | eingestellt | Vom Hersteller abgekündigt, im STN-Handbuch als „Obsolete" geführt; nur noch für Geräte im Bestand interessant |
vLinker-/VGATE-Klasse | div. | BLE | CAN, ISO-TP | L | optional | Qualitätsstreuung; nur nach Einzeltest |
**ELM327-Clones** | ELM327 (div. Clones) | BLE/WLAN/USB | CAN, ISO-TP (instabil) | L nur mit Warnung | **nicht unterstützt** | Kein zuverlässiges Multi-Frame, keine belastbare Security-Access-Sequenz, Firmware-Streuung → Ausschluss |

**Begründung des ELM327-Ausschlusses:** Ein fehlerhaftes ISO-TP-Flow-Control bei einem Schreibvorgang kann ein Steuergerät in einen undefinierten Zustand bringen. Das Risiko ist bei einem 8-€-Clone nicht kalkulierbar, und der Support-Aufwand frisst jede Marge. Wenn überhaupt: reiner Lesemodus, klar gekennzeichnet, ohne Schreibfunktionen.

### 1.1 Wie Klasse 1 an die App kommt (eigener Transport)

Klasse-1-Geräte sprechen nicht unser Drahtformat. Sie nehmen Textbefehle
(`AT …`, `ST …`) über eine serielle Strecke — BLE-GATT-UART, klassisches
Bluetooth-SPP oder, am Kabel, einen **USB-Seriell-Wandler (FTDI, Virtual COM
Port)** — und **machen ISO-TP selbst**: Segmentierung, Flow-Control und
Wiederzusammensetzung passieren im Dongle, die App sieht nur die fertige
Diagnosenachricht als Hex-Zeile.

Nicht USB-CDC: die gelisteten USB-Geräte (OBDLink EX, SX) sitzen hinter einem
FTDI-Chip, und ein CDC-ACM-Gerät bräuchte Line-Coding über die Klasse `CDC`.
Die Wandler-Chips der Billig-Clones — CP210x, CH340, PL2303 — bleiben draußen.

Deshalb bekommt Klasse 1 einen eigenen Transport, der **oberhalb** von
`CanTransport` ansetzt: eine Schnittstelle „Anfrage-Bytes rein, Antwort-Bytes
raus" statt CAN-Rahmen. Der Rust-Kern bleibt unverändert; seine ISO-TP-Schicht
wird auf diesem Weg nicht benutzt, weil der Dongle sie ersetzt.

Was das mitbringt und was es begrenzt:

- **Tiefe:** Lesen/Diagnose, R1-Service und — nach Einzelnachweis — R2 wie in §6.
  Kein R3, kein Flashen, kein Security Access (0x27): der Challenge/Response-Weg
  über einen Textbefehlssatz ist nicht belastbar prüfbar.
- **Tempo:** Textbefehle sind langsam; ein 4.095-Byte-Transfer dauert Sekunden
  statt Millisekunden. Für Live-Daten mit hoher Rate ungeeignet, für
  Momentaufnahmen und DTC-Listen ausreichend.
- **Prüfbarkeit:** Der ISO-TP-Stresstest aus §7 entfällt hier, weil auf diesem
  Weg unsere eigene Segmentierung nicht beteiligt ist. Stattdessen wird je Gerät
  die Antworttreue gegen einen festen Nachrichtenkorpus geprüft (gleiche
  Nachrichten, gleiche Bytes, je Firmware-Version).
- **Abgrenzung zu ELM327-Clones:** Die Aufnahme gilt für Geräte mit
  dokumentiertem STN-Befehlssatz und fester Firmware (OBDLink & Co.).
  Billig-Clones bleiben ausgeschlossen — dort ist weder Multi-Frame noch
  Fehlerverhalten reproduzierbar. Am Kabel wird das zweifach geprüft: nur
  Geräte mit FTDI-VID werden angesprochen, und die Firmware-Kennung muss per
  `STI` die Form `STN<id> vX.Y.Z` haben. `ATI` taugt dafür nicht — STN-Geräte
  antworten dort bewusst mit einem ELM327-String, der sich mit `STSATI` sogar
  frei setzen lässt.
- **Grenze der Prüfung:** Ein Clone, der `STI` nachahmt, käme durch. Der Filter
  ist eine Hürde, kein Beweis; die Produktentscheidung bleibt §1.

**Stand der Umsetzung (14.11.2026):** Der Transport ist im Android-Modul gebaut —
`StnKanal` samt ISO-TP-Zusammensetzung, `BleSerielleStrecke` und
`UsbSerielleStrecke` für die Zeilenbildung, `UsbSerialStrecke` für den
FTDI-Wandler und `StnKennung` als Gerätefilter. Geprüft ist das auf der JVM
gegen aufgezeichnete Antworten; **kein Gerät** hat diesen Weg bisher bestätigt,
und der Android-Anteil ist noch nicht gebaut (kein SDK in der Bauumgebung).

Praktisch heißt das: Klasse 1 ist der Weg für die Werkstatt, die schon einen
Dongle in der Schublade hat; Klasse 2 (Gateway oder mobiles SDK) bleibt der Weg
für alles, was schreibt oder flasht.

---

## 2. Klasse 2 — Profi-Passthru (J2534)

| Gerät | J2534 | Host | Busse | Tiefe | App-Status | Anmerkung |
|---|---|---|---|---|---|---|
Drew Technologies CarDAQ-Plus 3 | J2534-1/-2, DoIP, CAN FD | USB → **Gateway** | CAN, CAN FD, K-Line, DoIP, J1850 | L, S, C, F | nur über Gateway | Referenzgerät im Profisegment; OEM-Reprogrammierung |
Tactrix OpenPort 2.0 | J2534-1 | USB (Android via libusb) | CAN, ISO-TP, K-Line | L, S, C, F | zu zertifizieren (Android) | Verbreitet, günstig; iOS nur über Gateway |
Quantex ScanDoc (Compact/Full) | J2534 + DoIP | **SDK für Android & iOS** | CAN, CAN FD, ISO-TP, DoIP, K-Line | L, S, C, F | Kandidat Weg 1 | Hersteller liefert J2534-Bibliotheken u. a. für Android (arm64/x86) und iOS (XCFramework) — damit echte App-Integration ohne PC |
Mongoose-Plus (div. Marken) | J2534 | USB → Gateway | CAN, ISO-TP | L, S, C, F | nur über Gateway | Marken-OEM-Reprogrammierung |
Bosch MVCI / D-PDU API | ISO 22900-2 / J2534 | USB → Gateway | CAN, DoIP, K-Line | L, S, C, F | nur über Gateway | D-PDU API als standardisierte Alternative zu J2534 im Gateway |
Softing VCI / D-PDU API | ISO 22900-2, J2534, DoIP | USB → Gateway | CAN, DoIP | L, S, C, F | nur über Gateway | DoIP auch ohne VCI (reine IP-Anbindung) möglich |
Kvaser-Interfaces + J2534-DLL | J2534 | USB → Gateway | CAN, CAN FD | L, S, C (F je Lizenz) | nur über Gateway | Für Prüfstand/Entwicklung sehr nützlich |
PEAK PCAN + PCAN-PassThru | J2534 05.00 | USB → Gateway | CAN, CAN FD | L, S, C | nur über Gateway | Entwicklungs-/Prüfstands-Werkzeug |
Autel MaxiFlash / VCMI | proprietär (+ J2534) | USB/BT | CAN FD, DoIP | L, S, C, F | nicht unterstützt (ohne Partnervertrag) | SDK/Ökosystem geschlossen; Integration nur mit Kooperationsvertrag |
Launch X431 / X-Prog | proprietär | BT/USB | CAN, DoIP | L, S, C, F | nicht unterstützt (ohne Partnervertrag) | dito |

**Weg-1-Kandidaten (mobile SDK, echte App-Integration):** primär **Quantex ScanDoc** (belegte Android-/iOS-Builds), sekundär Hersteller mit BLE/WLAN-SDK (STN-Basis). Ziel: 2 unabhängige Anbieter, damit kein Single-Vendor-Risiko entsteht.

---

## 3. Klasse 3 — DoIP / Ethernet (typisch OEM-gebunden)

| Gerät | Marke | Host | Busse | Tiefe | App-Status | Anmerkung |
|---|---|---|---|---|---|---|
VAS 6154 / 6154A | VW/Audi/Škoda/SEAT | WLAN/USB | DoIP, CAN | L, S, C, F | nicht unterstützt | Nur mit ODIS + Herstellerlizenz; Nutzung in Dritt-App nicht gestattet |
VAS 5054A | VAG (älter) | USB/BT | CAN, K-Line | L, S, C, F | nicht unterstützt | Legacy |
BMW ICOM Next A/B | BMW/Mini | Ethernet/USB | DoIP, CAN, MOST | L, S, C, F | nicht unterstützt | Nur ISTA/E-Sys |
Mercedes XENTRY VCI / SDconnect C5 | Mercedes-Benz | WLAN/USB | DoIP, CAN | L, S, C, F | nicht unterstützt | Nur XENTRY; SERMI-Freigaben nötig |
DoIP **ohne** VCI (Fahrzeug direkt im LAN) | markenübergreifend | IP | DoIP (ISO 13400-2) | L, S | geplant (M2) | Sauberster Weg für neue Fahrzeuge: kein Dongle, nur Netzwerk + Routing Activation |

**Wichtige Produktaussage:** „DoIP ohne VCI" ist die einzige Klasse-3-Variante, die wir ohne Herstellervertrag anbieten können. Alles andere ist OEM-Software und wird in der App als „nicht unterstützt — Herstellersoftware erforderlich" geführt, statt Hoffnungen zu wecken.

---

## 4. Klasse 4 — OEM-Werkstatttools (nicht integrierbar)

Diese Geräte sind technisch oft J2534-fähig, rechtlich aber an Herstellersoftware und Lizenzmodelle gebunden. Sie erscheinen in der App **nicht** als unterstützte Hardware.

| Gerät | Marke | Anmerkung |
|---|---|---|
Ford VCM II / VCM 3 | Ford | FDRS-gebunden |
GM MDI / MDI 2 | GM/Opel | GDS2/Techline-gebunden |
Stellantis microPod / wiTECH | Stellantis | wiTECH-gebunden |
Toyota Mini-VCI / Techstream | Toyota/Lexus | Techstream-gebunden |
Honda HDS / i-HDS | Honda | HDS-gebunden |
Nissan Consult-III Plus | Nissan/Infiniti | Consult-gebunden |
Mitsubishi MUT-III | Mitsubishi | MUT-gebunden |
Mazda M-MDS | Mazda | M-MDS-gebunden |
JLR SDD / Pathfinder | Jaguar/Land Rover | SDD-gebunden |
Volvo DiCE / VIDA | Volvo | VIDA-gebunden |
PSA/Actia | Peugeot/Citroën/Opel | DiagBox-gebunden |
Renault CLIP | Renault/Dacia | CLIP-gebunden |
Hyundai/Kia GDS | Hyundai/Kia | GDS-gebunden |
Subaru Select Monitor | Subaru | SSM-gebunden |
Porsche PIWIS 3 | Porsche | PIWIS-gebunden |
Tesla Toolbox | Tesla | Toolbox-Abo, herstellergebunden |

**Nutzung in der App:** nur als **Anleitung/Hinweis** („Für diese Funktion ist Herstellersoftware X erforderlich") — nie als Integration. Das ist ehrlich, spart Support und ist rechtlich sauber.

---

## 5. Nutzfahrzeug (später, eigene Strategie)

| Gerät | Standard | Host | Tiefe | Status |
|---|---|---|---|---|
NEXIQ USB-Link 3 | RP1210, J1939 | USB → Gateway | L, S | M4 |
DG Technologies DPA 5 | RP1210 | USB → Gateway | L, S | M4 |
J1939 direkt via CAN-Interface | J1939 | je VCI | L | M4 |

RP1210 ist wie J2534 eine Windows-API → gleiche Gateway-Logik.

---

## 6. Was die App je Klasse kann

| Fähigkeit | Klasse 1 (BLE/WLAN/USB) | Klasse 2 (Passthru) | Klasse 3 (DoIP) | Klasse 4 (OEM) |
|---|---|---|---|---|
DTC lesen/löschen | ✅ | ✅ | ✅ | ❌ (Herstellersoftware) |
Live-Daten / Logging | ✅ | ✅ | ✅ | ❌ |
Service-Rückstellung (R1) | ✅ | ✅ | ✅ | ❌ |
R2-Funktionen (Backup-Pflicht) | ⚠️ nur bei stabiler ISO-TP | ✅ | ✅ | ❌ |
Codierung (R3) | ❌ | ✅ | ✅ | ❌ |
Flashen (R3) | ❌ | ✅ (OEM-Datei, Abo) | ⚠️ je Fahrzeug | ❌ |
SERMI-Funktionen (R4) | ❌ | ✅ + SERMI | ✅ + SERMI | ❌ |

---

## 7. Zertifizierungs-Checkliste pro Gerät

Bevor ein Gerät in die Matrix „getestet" bekommt:

1. Herstellervertrag/NDA, SDK-Lizenz zur Auslieferung in einer Dritt-App geklärt.
2. Firmware-Mindestversion festgelegt und in der App prüfbar.
3. Capability-Report des Geräts gegen das eigene Capability-Modell abgeglichen (CAN FD? DoIP? Pins? Spannungsmessung?).
4. ISO-TP-Stresstest: 4.095-Byte-Transfer, 100 Wiederholungen, Fehlerrate 0.
5. Hot-Plug-/Reconnect-Test (Werkstatt zieht Kabel im Betrieb).
6. Spannungs-/Busfehler-Test (Kurzschluss CAN-H/L simuliert).
7. iOS- und Android-Matrix gefüllt (nicht „geht auf Android, also geht es überall").
8. Support-Doku: Was tun bei „VCI nicht gefunden" — Self-Test in der App.
9. Rückgabequote/DOA-Quote des Lieferanten bekannt.
10. Eintrag in der Matrix mit Datum, Tester, Fahrzeug, Ergebnis.

---

## 8. Empfehlung in einem Satz

**Version 1:** zwei bis drei zertifizierte BLE-/WLAN-VCIs (STN-Klasse), dazu ein USB-Modell am FTDI-Wandler (OBDLink EX, Status: zu zertifizieren), + ein Passthru-Gerät mit mobilem SDK → Diagnose und Service-Rückstellung überall dort, wo die Werkstatt heute schon einen Dongle in der Schublade hat.
**Version 2:** Gateway im LAN → alle J2534-Geräte und DoIP werden nutzbar, inklusive Flashen.
**Version 3:** eigene Hardware nur, wenn Content und Kundenbasis die Investition tragen.

*Gerätenennungen dienen der technischen Planung; Marken- und Lizenzrechte liegen bei den jeweiligen Herstellern. Verfügbarkeit von SDKs und mobilen Builds ist vor der Aufnahme in die Support-Matrix vertraglich zu bestätigen.*
