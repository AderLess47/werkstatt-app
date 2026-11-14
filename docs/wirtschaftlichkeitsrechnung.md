# Wirtschaftlichkeitsrechnung Werkstatt-App

**Stand:** 14.11.2026 · Ergänzung zu [`werkstatt-app-plan.md`](werkstatt-app-plan.md) §9
**Rechentool:** [wirtschaftlichkeit.xlsx](../model/wirtschaftlichkeit.xlsx) (11 Blätter) · **Quelle:** [wirtschaftlichkeit.py](../model/wirtschaftlichkeit.py)
*Alle Beträge EUR netto. Annahmen und Grenzen: Blatt `09_Hinweise`.*

---

## 1. Ergebnis in fünf Sätzen

1. **Die Unit Economics sind gut:** 120 €/Monat je Werkstatt, 85 % Deckungsbeitrag, Kunde hat seinen Akquise-Aufwand (700 €) nach **7 Monaten** bezahlt.
2. **Das Problem ist nicht der Kunde, sondern die Zeit:** Der Fixkostenblock (Content + Overhead + Vertrieb) liegt bei **1,22 Mio €/Jahr** und startet am Launch-Tag — die Kundenbasis wächst aber nur mit 8–22 Betrieben pro Monat.
3. **Deshalb liegt der Break-even bei 1.050–1.500 Werkstätten** — das sind **5–8 % des adressierbaren Marktes** in DACH und realistisch **4–6 Jahre** nach Markteintritt.
4. **Der Kapitalbedarf ist 1,5- bis 2-mal so hoch wie die Entwicklung:** 3,4 Mio € (schlanke Variante) bis 13,6 Mio € (Herstellerniveau) — nicht 1,3–3,2 Mio € Entwicklungssumme.
5. **Empfehlung:** schlanker Einstieg (Variante V1 oder V2, Stufe A/B), Preis ab 150 €/Monat statt 49-€-Einstieg, Vertrieb über Fachhandel/Ketten — oder die App im Portfolio eines etablierten Werkstattausrüsters (White-Label). Als eigenständiges Startup mit Direktvertrieb trägt das Modell nicht.

---

## 2. Entwicklungsprognose

| Position | V1 Service-Fokus | V2 Empfohlen | V3 Herstellerniveau |
|---|---|---|---|
Dauer bis Launch | 12 Monate | 18 Monate | 24 Monate
Team in der Spitze | 7,5 FTE | 9,5 FTE | 10,3 FTE
Personal | 792 T€ | 1.493 T€ | 2.137 T€
Sachkosten (Lab, Fahrzeuge, Prüfstand, SDK, Cloud, Recht) | 264 T€ | 396 T€ | 576 T€
Launch-Marketing | 150 T€ | 150 T€ | 180 T€
Puffer 10 % | 121 T€ | 204 T€ | 289 T€
**Entwicklung gesamt** | **1.327 T€** | **2.242 T€** | **3.182 T€**

Vollkosten je FTE-Jahr: Android/iOS/Backend 105 T€, Rust/Protokoll 118 T€, Security 125 T€, PM 112 T€, Kfz-Meister 92 T€, QA 85 T€, Content 88 T€.

**Einordnung:** V2 ist der empfohlene Zuschnitt aus dem Plan (Service + Codierung R2 + Gateway). V3 enthält Flashen/Passthru und das SERMI-Modul — der Aufpreis von **+940 T€** ist der Preis für „Herstellerniveau".

---

## 3. Content- und Lizenzkosten je Marktabdeckung (der eigentliche Kostentreiber)

| Kostenposition p.a. | Stufe A<br>20 Modelle / 5 Marken | Stufe B<br>60 / 12 | Stufe C<br>150 / 25 | Stufe D<br>300+ / 40 |
|---|---|---|---|---|
Content-Team (FTE-Jahre) | 132 T€ | 220 T€ | 352 T€ | 528 T€
Fahrzeugzugang, Mietfahrzeuge, Prüfstand | 60 T€ | 140 T€ | 280 T€ | 480 T€
OEM-RMI-Abos für die eigene Redaktion | 8 T€ | 20 T€ | 45 T€ | 75 T€
Datenanbieter-Lizenz | – | 90 T€ | 180 T€ | 300 T€
ODX/PDX-Lizenzen | – | 40 T€ | 90 T€ | 150 T€
QA / Feldtests | 20 T€ | 30 T€ | 50 T€ | 80 T€
**Summe p.a.** | **245 T€** | **540 T€** | **997 T€** | **1.613 T€**
Listenpreis-Mix (EUR/Monat) | 106 | 120 | 130 | 136

**Wichtig:** Diese Kosten sind Fixkosten, keine variablen. Sie fallen an, **bevor** der erste Kunde mit dieser Abdeckung kommt. Die Abdeckung ist damit eine Wette auf die Kundenbasis — nicht eine Investition, die sich mit dem ersten Verkauf amortisiert.

### Inkrementalbetrachtung — die entscheidungsrelevante Sicht

| Schritt | Mehrkosten p.a. | Zusätzliche Kunden bis Break-even | Bewertung |
|---|---|---|---|
A → B | +295 T€ | **+240** | Tragfähig. 60 Modelle sind die Eintrittskarte für Mehrmarken-Werkstätten (Codierung R2).
B → C | +457 T€ | **+339** | Sinnvoll ab ~1.200 Bestandskunden; Asien-Abdeckung hebt Preis und senkt Abwanderung.
C → D | +616 T€ | **+434** | Erst ab ~1.800 Kunden. Vorher ist Breite ohne zahlende Basis Margenvernichtung.

**Regel:** Ausbaustufe erst, wenn die zusätzlich benötigten Kunden in unter 12 Monaten erreichbar sind.

---

## 4. Abo-Preise und Deckungsbeitrag

| Paket | EUR/Monat | EUR/Jahr | Kundenanteil | Inhalt |
|---|---|---|---|---|
Basis | 49 | 588 | 30 % | Diagnose, 20 Service-Funktionen, 1 Nutzer, 1 VCI
Pro | 129 | 1.548 | 55 % | voller Service-Katalog, Codierung R2, 3 Nutzer, Doku/Audit
Werkstatt Plus | 249 | 2.988 | 15 % | Flashen, SERMI-Modul, Mehrmandant, API, Vorrang-Support

| Kennzahl | Wert | Rechnung |
|---|---|---|
Listenpreis-Mix | 123 €/Monat | 0,3 × 49 + 0,55 × 129 + 0,15 × 249
nach 15 % Rabatt (Jahresvorauszahlung/Kette) | 104,55 €/Monat | —
+ Add-ons (Markenpakete, Wartung, Zweit-VCI) | +16 €/Monat | —
**ARPA (Basisfall Stufe B)** | **120 €/Monat = 1.440 €/Jahr** | —
Variable Kosten je Kunde | 212 €/Jahr | Cloud 42 + Zahlung 28 + Support 87 + Onboarding 55
**Deckungsbeitrag** | **1.228 €/Jahr (85,3 %)** | Umsatz − variabel
LTV (12 % Churn) | 10.233 € | DB ÷ Churn
CAC je Neukunde | 700 € | Vertrieb 160 T€ + Marketing 120 T€ bei ~400 Abschlüssen p.a.
LTV/CAC · CAC-Payback | 14,6 · 6,8 Monate | —

**Marktbenchmark:** Freie Werkstätten zahlen heute 1.000–2.500 €/Jahr für Diagnosesoftware (Bosch ESI[tronic], Hella Gutmann, Autel-Abos). 1.440 €/Jahr liegt im Mittelfeld — die Annahme ist marktkonform, nicht optimistisch.

---

## 5. Break-even-Matrix: Ausbauvariante × Marktabdeckung

Break-even-Kunden = (Content + Overhead + Vertrieb + Entwicklung ÷ 5 Jahre) ÷ Deckungsbeitrag.

| | Stufe A | Stufe B | Stufe C | Stufe D |
|---|---|---|---|---|
**V1 Service-Fokus** | **1.122** | 1.209 | 1.440 | 1.801
**V2 Empfohlen** | 1.295 | **1.358** | 1.576 | 1.930
**V3 Herstellerniveau** | 1.472 | 1.511 | 1.715 | 2.062

In Prozent des DACH-SAM (19.000 Betriebe): 5,9 % (V1/A) bis 10,9 % (V3/D). Bei V2/B sind es **7,1 %**.

### Der stärkste Hebel ist der Preis (V2 / Stufe B)

| ARPA je Werkstatt | Break-even Kunden | Anteil SAM | Bewertung |
|---|---|---|---|
90 €/Monat | 1.921 | 10,1 % | Nicht marktfähig für den Funktionsumfang.
105 €/Monat | 1.591 | 8,4 % | Grenzfall.
**120 €/Monat** | **1.358** | **7,1 %** | Basis-Annahme des Modells.
135 €/Monat | 1.184 | 6,2 % | Erreichbar mit höherem Plus-Anteil.
150 €/Monat | 1.050 | 5,5 % | Plus-Paket-Fokus + VCI-Bundle + Content-Abos.
180 €/Monat | 856 | 4,5 % | Zielbild: die Werkstatt kauft Kompetenz, nicht Funktionen.
210 €/Monat | 722 | 3,8 % | Nur mit Herstellerniveau-Inhalten (SERMI, Codierung, Flashen).

**Merksatz:** Das Abo bezahlt die Datenpflege, nicht die Software. Bei 60 Modellen Content kostet die Abdeckung 540 T€/Jahr — unabhängig davon, wie viele Funktionen in der App stehen.

---

## 6. Cashflow, Kapitalbedarf und Vertriebsweg

Modell: 72 Monate, 1 % Abwanderung/Monat, ARPA steigt mit Upsell. Szenarien unterscheiden nur den Vertriebsweg:

- **S1 Direktvertrieb** — 2 FTE, 8 Abschlüsse/Monat (realistisch für Werkstattsoftware mit Demo vor Ort)
- **S2 + Fachhandel** — ab Monat 13 zusätzlich 14/Monat über Ausrüster/Teilegroßhandel
- **S3 + Ketten-Deals** — zusätzlich drei Deals à 250 Betriebe (Monat 18, 30, 42)
- **S4 Lean** — 1 Vertriebs-FTE (156 T€ statt 280 T€), dafür früher Fachhandel

| Variante / Abdeckung | S1 Direkt | S2 + Fachhandel | S3 + Ketten | S4 Lean |
|---|---|---|---|---|
V1 / A | kein BE (5,82 M€) | Monat 67 (4,53 M€) | **Monat 42 (3,87 M€)** | Monat 40 (3,38 M€)
V2 / A | kein BE (6,93 M€) | kein BE (5,91 M€) | Monat 48 (5,26 M€) | Monat 46 (4,70 M€)
V2 / B | kein BE (8,56 M€) | kein BE (7,38 M€) | Monat 55 (6,34 M€) | Monat 56 (5,79 M€)
V3 / B | kein BE (9,71 M€) | kein BE (8,83 M€) | Monat 61 (7,90 M€) | Monat 62 (7,28 M€)
V3 / C | kein BE (12,37 M€) | kein BE (11,41 M€) | Monat 66 (10,15 M€) | Monat 68 (9,52 M€)
V3 / D | kein BE (16,02 M€) | kein BE (15,00 M€) | kein BE (13,56 M€) | kein BE (13,05 M€)

*Werte in Klammern: maximaler Kapitalbedarf (tiefster Punkt der kumulierten Liquidität). „kein BE" = innerhalb von 72 Monaten nicht erreicht.*

**Drei Aussagen, die man daraus mitnehmen kann:**

1. **Reiner Direktvertrieb funktioniert nicht.** 8 Abschlüsse/Monat sind realistisch, reichen aber nicht: nach 6 Jahren sind es 322 Kunden, benötigt werden 1.358. Der Engpass ist der Vertrieb, nicht das Produkt.
2. **Der Vertriebsweg verändert das Ergebnis stärker als die Technik.** Zwischen S1 und S3 liegen bei V2/B fast 2,2 Mio € Kapitalbedarf.
3. **Herstellerniveau als Startup ist nicht finanzierbar.** V3/D braucht 13,6 Mio € — das ist eine Größenordnung für einen etablierten Ausrüster, nicht für ein Gründerteam.

---

## 7. Die empfohlene Konstellation — und was sie kostet

V2 Empfohlen / Stufe B / S3, drei Preisniveaus:

| ARPA | Break-even Kunden | Cash-Break-even | Kapitalbedarf max. | Kumuliert nach 72 Monaten |
|---|---|---|---|---|
120 € | 1.358 | Monat 55 | −6,34 M€ | −5,75 M€
150 € | 1.050 | Monat 48 | −6,07 M€ | −4,61 M€
180 € | 856 | Monat 47 | −5,85 M€ | −3,46 M€

Der Preis verbessert das **Endergebnis** deutlich (−5,75 → −3,46 M€), den **Kapitalbedarf** aber kaum: der wird von Entwicklungsdauer und Anlaufverlusten bestimmt, nicht von der Marge. Genau deshalb ist die Verkürzung der Anlaufphase (Kanal, Ketten, Partner) wichtiger als die letzte Preiserhöhung.

### Drei Wege, die den Break-even in realistische Reichweite bringen

| Weg | Was passiert | Wirkung im Modell | Preis dafür |
|---|---|---|---|
**A — Preis** | 150–180 €/Monat statt 120 € | Break-even bei 856–1.050 Kunden (4,5–5,5 % SAM) statt 1.358 | Setzt voraus, dass die App Kompetenz und Nachweisbarkeit verkauft (Audit, SERMI-Tauglichkeit, Doku) — nicht Funktionslisten
**B — Kanal** | Fachhandel/Ausrüster und Ketten-Deals statt eigener Vertriebsmannschaft | Cash-BE 42–56 statt >72 Monate; Kapitalbedarf sinkt um 0,5–2 Mio € | 15–25 % Händlerrabatt, aber 140–280 T€ Vertriebsfixkosten gespart
**C — Partner/White-Label** | App im Portfolio eines etablierten Werkstattausrüsters | Entwicklung fremdfinanziert, Vertrieb und Markenaufbau entfallen | Marge je Kunde sinkt; Produktkontrolle geteilt

**Empfehlung:** Weg **B + C kombiniert**. Variante V1 oder V2 mit Stufe A/B, Preis ab 150 €/Monat, Fachhandel ab Monat 10, und parallel ein White-Label-Gespräch mit einem Werkstattausrüster. Kapitalbedarf in dieser Konstellation: **3,4–5,5 Mio €**, Break-even Monat 40–48. Das ist finanzierbar — die im Plan genannten 2,5 Mio € Entwicklungssumme sind es nicht.

---

## 8. Sensitivität (Basis V2 / Stufe B)

| Stellschraube | Veränderung | Break-even Kunden | Differenz |
|---|---|---|---|
Basis | — | 1.358 | —
Preis −20 % | 96 €/Monat | 1.774 | +416
Preis +20 % | 144 €/Monat | 1.100 | −258
Entwicklung +25 % | 2,80 Mio € | 1.449 | +91
Content +25 % | 675 T€ p.a. | 1.468 | +110
Support +50 % | DB −44 €/Jahr | 1.408 | +50

**Erkenntnis:** Preisdruck ist mit Abstand der teuersten Fehler. Entwicklungsüberschreitungen sind verkraftbar; ein Preis, der die Content-Kosten nicht trägt, ist es nicht.

**Churn wirkt anders als erwartet:** Er verändert die Break-even-Kundenzahl nicht (Gleichgewichtsbetrachtung), sondern den LTV und den Akquisebedarf. Bei 12 % Abwanderung liegt der LTV bei 10.233 €, bei 18 % nur bei 6.822 €. Bei 1.358 Kunden müssen jährlich **163 (12 %) bzw. 244 (18 %)** Neuverträge geschlossen werden, nur um den Bestand zu halten. Schlechter Support ist deshalb teurer als eine fehlende Funktion.

---

## 9. Was in dieser Rechnung nicht steckt

- **Steuern, Finanzierungskosten, Working Capital** — für eine Investorenrunde um 13-Wochen-Liquiditätsplanung und Steuern zu ergänzen.
- **Hardware-Marge** aus dem VCI-Verkauf (bewusst konservativ weggelassen; Größenordnung +90 € je Gerät).
- **SERMI-Zertifizierung der Kundenbetriebe** — zahlt der Betrieb, nicht der Anbieter. Sie ist aber Umsatzvoraussetzung für das Plus-Paket.
- **Marktsättigung** — das Modell unterstellt kein Deckelwachstum; oberhalb von 1.900 Kunden steigen Support- und Vertriebskosten überproportional.
- **Rechtsstreit-, Rückstellungs- und Produkthaftungsrisiken** aus fehlerhafter Codierung (Versicherung einkalkuliert: 45 T€/Jahr im Overhead).

**Validierungsbedarf vor Investitionsfreigabe:** SAM 19.000 Betriebe (ZDK/WKO/AGVS prüfen), Content-Kosten (±50 % Verhandlungsspielraum bei Datenanbietern), Zahlungsbereitschaft (10–15 Werkstätten interviewen, bevor der Preis fixiert wird).

---

## 10. Dateien

| Datei | Inhalt |
|---|---|
[wirtschaftlichkeit.xlsx](../model/wirtschaftlichkeit.xlsx) | 11 Blätter: Ergebnis, Annahmen, Entwicklung, Content, Preise, Break-even, Cashflow, Szenarien, Sensitivität, Hinweise, Empfehlung
[wirtschaftlichkeit.py](../model/wirtschaftlichkeit.py) | Rechenmodell, erzeugt die Arbeitsmappe — Annahmen ändern und `python model/wirtschaftlichkeit.py` ausführen

*Die Arbeitsmappe enthält berechnete Werte (keine Excel-Formeln), damit sie überall identisch angezeigt wird. Änderungen an Annahmen gehören in das Skript; die Kontrollausgabe beim Lauf zeigt alle Kennzahlen zur Gegenprüfung.*
