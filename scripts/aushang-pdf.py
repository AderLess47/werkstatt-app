#!/usr/bin/env python3
"""Baut aus dem Aushang ein einseitiges A4-PDF für die Werkstattwand.

Quelle des Inhalts ist `docs/aushang-wartezeiten.md`. Dieses Skript setzt ihn
bewusst nicht automatisch aus dem Markdown zusammen: ein Aushang ist Layout, und
die Zeilenumbrüche, Schriftgrade und die Tabellenbreite sind hier das Ergebnis
mehrerer Probedrucke. Was hier steht, muss deshalb zum Markdown passen — die
Prüfzeile am Ende stellt sicher, dass alles auf **eine** Seite passt.

Aufruf (Abhängigkeiten liegen nicht im Repo):

    python3 -m pip install --target .pydeps reportlab
    PYTHONPATH=.pydeps python3 scripts/aushang-pdf.py

Ergebnis: `docs/aushang-wartezeiten.pdf`, eine Seite, A4 hoch.
"""

import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfgen import canvas

BREITE, HOEHE = A4
RAND = 42
RECHTS = BREITE - RAND
UNTEN = 46

TEXT = (0.10, 0.10, 0.10)
GRAU = (0.42, 0.42, 0.42)
LINIE = (0.75, 0.75, 0.75)
FUELLUNG = (0.94, 0.94, 0.94)

TITEL = "Wartezeiten am Prüfplatz"
UNTERZEILE = "Aushang für die Werkstattwand · Stand 14.11.2026"

EINLEITUNG = (
    "Solange eine Wartezeile steht („Suche …“, „Sende …“, „Warte auf …“), "
    "arbeitet die App und sagt, worauf sie wartet. Die Knöpfe sind währenddessen "
    "absichtlich gesperrt."
)

# Die Zellen sind gegenüber dem Markdown gekürzt, damit sie in ihre Spalte
# passen („bis 5 s, danach 3 s“ statt „1. Mal etwa 5 s, danach etwa 3 s“) —
# inhaltlich dieselbe Aussage, nur ohne Füllwörter.
SPALTEN = ("Vorgang", "normal", "spätestens")
ZEILEN = [
    ("USB-Gerät öffnen, Freigabe", "sofort nach „Zulassen“", "Dialog offen: bis 30 s"),
    ("Schnelltest: Spannung, Steuergerät", "unter 1 Sekunde", "nach etwa 1 s"),
    ("Dienst lesen — roher USB-Weg", "unter 1 Sekunde", "nach etwa 5 s"),
    ("Dienst lesen — OBDLink am Kabel", "1–2 Sekunden", "bis 5 s, danach 3 s"),
    ("Bluetooth: suchen und verbinden", "2–5 Sekunden", "nach etwa 25 s"),
]

KERN = (
    "Die Kern-Prüfung beim Start hat kein Zeitlimit — sie rechnet im Telefon und ist "
    "normalerweise in unter einer Sekunde fertig. Steht dort länger „Prüfe Kern …“, "
    "stimmt etwas mit der Installation nicht."
)

REGELN = [
    ("Nicht mehrfach drücken", "und nicht abziehen, solange die Zeile steht. Jeder "
     "zweite Druck macht die Wartezeit länger, nicht kürzer."),
    ("Kommt ein Fehler,", "gilt diese Reihenfolge: Zündung an → Stecker und Kabel "
     "prüfen → anderes Fahrzeug → Gegenprobe am PC."),
    ("Nie ein Befund, immer nur die Zeile?", "Das ist ein Hänger und der einzige echte "
     "Fall für den Support: notieren, wie lange die Zeile stand, App schließen, "
     "Selbsttest-Bericht schicken."),
    ("Roter „Steuergerät“-Befund ohne Fahrzeug ist normal", "— auf dem Tisch antwortet "
     "niemand. Bordspannung und Verbindung sind trotzdem geprüft."),
]

ZWEI_WEGE = (
    "„OBDLink (Klasse 1)“ und „Eigenes Drahtformat (Weg A)“ lesen dieselbe Kennung. "
    "Antwortet einer und der andere nicht, liegt es am Weg, nicht am Steuergerät: "
    "mit beiden Wegen wiederholen und beide Berichte mitschicken."
)

AUFSCHUB = (
    "Bittet das Steuergerät um Aufschub (in der Wartezeile steht „0x78“), wartet die "
    "App bis zu dreimal je 5 Sekunden länger — das ist normal und kein Hänger."
)

FUSS = (
    "Aushang zur Werkstatt-Anleitung §14 · Ausführlich: docs/selbsttest-anleitung.md · "
    "Prüfplan: docs/geraetepruefung.md · Die Zeiten sind Obergrenzen, an denen die App "
    "aufgibt — keine Messwerte vom Fahrzeug."
)


def umbrechen(text, font, groesse, breite):
    """Bricht Text auf die angegebene Breite; gibt die Zeilen zurück."""
    woerter, zeilen, aktuell = text.split(), [], ""
    for wort in woerter:
        versuch = wort if not aktuell else aktuell + " " + wort
        if pdfmetrics.stringWidth(versuch, font, groesse) <= breite:
            aktuell = versuch
        else:
            if aktuell:
                zeilen.append(aktuell)
            aktuell = wort
    if aktuell:
        zeilen.append(aktuell)
    return zeilen


def zeichnen(pdf, font_titel, font_fett, font_text):
    y = HOEHE - RAND

    pdf.setFillColorRGB(*LINIE)
    pdf.rect(RAND, y - 6, RECHTS - RAND, 4, stroke=0, fill=1)
    y -= 26

    pdf.setFillColorRGB(*TEXT)
    pdf.setFont(font_titel, 30)
    pdf.drawString(RAND, y - 22, TITEL)
    y -= 34

    pdf.setFont(font_text, 12.5)
    pdf.setFillColorRGB(*GRAU)
    pdf.drawString(RAND, y - 10, UNTERZEILE)
    y -= 34

    # Einleitung
    pdf.setFillColorRGB(*TEXT)
    pdf.setFont(font_text, 12)
    for zeile in umbrechen(EINLEITUNG, font_text, 12, RECHTS - RAND):
        pdf.drawString(RAND, y - 12, zeile)
        y -= 17
    y -= 12

    # Tabelle
    pdf.setFont(font_fett, 15)
    pdf.drawString(RAND, y - 14, "Wie lange es dauern darf")
    y -= 30

    breiten = (240, 130, RECHTS - RAND - 240 - 130)
    # Kein Text darf aus seiner Spalte laufen — sonst steht er im Rand oder
    # wird beim Drucken abgeschnitten. Lieber hier abbrechen als still daneben.
    for spalte, breite in zip(SPALTEN, breiten):
        if pdfmetrics.stringWidth(spalte, font_fett, 11.5) > breite - 8:
            sys.exit(f"Spaltenkopf zu breit: {spalte!r}")
    for vorgang, normal, spaet in ZEILEN:
        for wert, breite, font in (
            (vorgang, breiten[0], font_text),
            (normal, breiten[1], font_text),
            (spaet, breiten[2], font_fett),
        ):
            if pdfmetrics.stringWidth(wert, font, 11.5) > breite - 8:
                sys.exit(f"Zelle zu breit für ihre Spalte: {wert!r}")
    x = RAND
    pdf.setFont(font_fett, 11.5)
    for spalte, breite in zip(SPALTEN, breiten):
        pdf.drawString(x, y - 11, spalte)
        x += breite
    y -= 18
    pdf.setStrokeColorRGB(*LINIE)
    pdf.setLineWidth(1.2)
    pdf.line(RAND, y, RECHTS, y)
    y -= 2

    for i, (vorgang, normal, spaet) in enumerate(ZEILEN):
        hoehe = 24
        if i % 2 == 0:
            pdf.setFillColorRGB(*FUELLUNG)
            pdf.rect(RAND, y - hoehe + 6, RECHTS - RAND, hoehe, stroke=0, fill=1)
        pdf.setFillColorRGB(*TEXT)
        pdf.setFont(font_text, 11.5)
        pdf.drawString(RAND + 4, y - 11, vorgang)
        pdf.drawString(RAND + 4 + breiten[0], y - 11, normal)
        pdf.setFont(font_fett, 11.5)
        pdf.drawString(RAND + 4 + breiten[0] + breiten[1], y - 11, spaet)
        y -= hoehe

    y -= 2
    pdf.setLineWidth(1.2)
    pdf.line(RAND, y, RECHTS, y)
    y -= 20

    pdf.setFillColorRGB(*GRAU)
    pdf.setFont(font_text, 10.5)
    for zeile in umbrechen(KERN, font_text, 10.5, RECHTS - RAND):
        pdf.drawString(RAND, y - 10, zeile)
        y -= 14
    y -= 14

    # Regeln
    pdf.setFillColorRGB(*TEXT)
    pdf.setFont(font_fett, 15)
    pdf.drawString(RAND, y - 14, "Vier Regeln")
    y -= 32

    for nummer, (fett, rest) in enumerate(REGELN, start=1):
        pdf.setFont(font_fett, 12.5)
        pdf.drawString(RAND, y - 12, f"{nummer}.")
        x_text = RAND + 20
        breite = RECHTS - x_text
        # Erste Zeile beginnt mit dem fett gesetzten Anfang.
        kopf = fett + " " + rest
        zeilen = umbrechen(kopf, font_text, 12, breite)
        for j, zeile in enumerate(zeilen):
            if j == 0 and zeile.startswith(fett):
                pdf.setFont(font_fett, 12)
                pdf.drawString(x_text, y - 12, fett)
                versatz = pdfmetrics.stringWidth(fett + " ", font_fett, 12)
                pdf.setFont(font_text, 12)
                pdf.drawString(x_text + versatz, y - 12, zeile[len(fett) + 1:])
            else:
                pdf.setFont(font_text, 12)
                pdf.drawString(x_text, y - 12, zeile)
            y -= 16
        y -= 8

    y -= 4

    # Zwei Wege
    pdf.setFont(font_fett, 15)
    pdf.drawString(RAND, y - 14, "Wenn zwei Wege verschieden antworten")
    y -= 32
    pdf.setFont(font_text, 11.5)
    for zeile in umbrechen(ZWEI_WEGE, font_text, 11.5, RECHTS - RAND):
        pdf.drawString(RAND, y - 11, zeile)
        y -= 15
    y -= 14

    # 0x78-Kasten
    zeilen = umbrechen(AUFSCHUB, font_text, 11.5, RECHTS - RAND - 20)
    kasten = len(zeilen) * 15 + 16
    pdf.setFillColorRGB(*FUELLUNG)
    pdf.rect(RAND, y - kasten + 6, RECHTS - RAND, kasten, stroke=0, fill=1)
    pdf.setFillColorRGB(*TEXT)
    pdf.setFont(font_text, 11.5)
    yy = y
    for zeile in zeilen:
        pdf.drawString(RAND + 10, yy - 11, zeile)
        yy -= 15
    y -= kasten + 14

    # Fuß
    pdf.setStrokeColorRGB(*LINIE)
    pdf.setLineWidth(0.6)
    pdf.line(RAND, y + 4, RECHTS, y + 4)
    pdf.setFillColorRGB(*GRAU)
    pdf.setFont(font_text, 8.2)
    for zeile in umbrechen(FUSS, font_text, 8.2, RECHTS - RAND):
        pdf.drawString(RAND, y - 8, zeile)
        y -= 10

    return y


def main():
    ziel = Path(__file__).resolve().parent.parent / "docs" / "aushang-wartezeiten.pdf"
    font_titel, font_fett, font_text = "Helvetica-Bold", "Helvetica-Bold", "Helvetica"

    # Erst rechnen, dann schreiben: passt der Inhalt nicht, gibt es kein PDF.
    class Probe(canvas.Canvas):
        def __init__(self):
            super().__init__(str(ziel), pagesize=A4)

        def showPage(self):  # verhindert eine zweite Seite beim Probelauf
            self._probe_ende = True

    probe = Probe()
    ende = zeichnen(probe, font_titel, font_fett, font_text)
    probe.save()
    if ende < UNTEN:
        sys.exit(f"Passt nicht auf eine Seite: {ende:.1f} pt unter der Unterkante "
                 f"(Grenze {UNTEN} pt). Schriftgrade oder Texte kürzen.")

    pdf = canvas.Canvas(str(ziel), pagesize=A4)
    pdf.setTitle("Wartezeiten am Prüfplatz — Aushang")
    pdf.setAuthor("Werkstatt VCI")
    pdf.setSubject("Wartezeiten und Abbruchgrenzen des Selbsttests")
    zeichnen(pdf, font_titel, font_fett, font_text)
    pdf.showPage()
    pdf.save()
    print(f"{ziel} geschrieben · Restplatz über der Unterkante: {ende - UNTEN:.1f} pt")


if __name__ == "__main__":
    main()
