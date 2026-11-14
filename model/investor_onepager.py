#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Erzeugt docs/investor-summary.pdf — ein Investor-One-Pager (A4, genau eine Seite).
Zahlen stammen aus model/wirtschaftlichkeit.py (Konstellation V2 / Stufe B / S4, ARPA 150 EUR).
"""
import os

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "docs", "investor-summary.pdf")

NAVY = colors.HexColor("#1F3864")
BLUE = colors.HexColor("#2E5C8A")
GREY = colors.HexColor("#555555")
LIGHT = colors.HexColor("#EDF1F7")
RULE = colors.HexColor("#D0D7E2")

W = 182 * mm

h = ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=9.0, leading=11,
                   textColor=NAVY, spaceBefore=5, spaceAfter=1.6)
body = ParagraphStyle("b", fontName="Helvetica", fontSize=7.9, leading=9.9, textColor=colors.HexColor("#1a1a1a"))
small = ParagraphStyle("s", fontName="Helvetica", fontSize=6.5, leading=7.8, textColor=GREY)
title = ParagraphStyle("t", fontName="Helvetica-Bold", fontSize=15, leading=17, textColor=colors.white)
sub = ParagraphStyle("u", fontName="Helvetica", fontSize=7.2, leading=9, textColor=colors.HexColor("#D6E0EF"))
kpi_v = ParagraphStyle("kv", fontName="Helvetica-Bold", fontSize=12.5, leading=14, textColor=NAVY, alignment=1)
kpi_l = ParagraphStyle("kl", fontName="Helvetica", fontSize=6.3, leading=7.4, textColor=GREY, alignment=1)


def tbl(data, widths, header=True, fs=7.1, lead=8.6):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    st = [
        ("FONTNAME", (0, 0), (-1, -1), "Helvetica"),
        ("FONTSIZE", (0, 0), (-1, -1), fs),
        ("LEADING", (0, 0), (-1, -1), lead),
        ("TEXTCOLOR", (0, 0), (-1, -1), colors.HexColor("#1a1a1a")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 1.9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 1.9),
        ("LEFTPADDING", (0, 0), (-1, -1), 3),
        ("RIGHTPADDING", (0, 0), (-1, -1), 3),
        ("LINEBELOW", (0, 0), (-1, -1), 0.3, RULE),
    ]
    if header:
        st += [("BACKGROUND", (0, 0), (-1, 0), LIGHT),
               ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
               ("TEXTCOLOR", (0, 0), (-1, 0), NAVY)]
    t.setStyle(TableStyle(st))
    return t


story = []

# --- Kopf -------------------------------------------------------------------
head = Table([[Paragraph("Werkstatt-App", title)],
              [Paragraph("Native Android- und Apple-App für Werkstätten: Diagnose, Service-Rückstellung, "
                         "ECU-Codierung &nbsp;·&nbsp; Investor One-Pager &nbsp;·&nbsp; Stand 14.11.2026", sub)]],
             colWidths=[W])
head.setStyle(TableStyle([
    ("BACKGROUND", (0, 0), (-1, -1), NAVY),
    ("LEFTPADDING", (0, 0), (-1, -1), 8),
    ("TOPPADDING", (0, 0), (0, 0), 7),
    ("BOTTOMPADDING", (0, 1), (0, 1), 7),
    ("TOPPADDING", (0, 1), (0, 1), 0),
]))
story.append(head)
story.append(Spacer(1, 4))

# --- KPI-Zeile --------------------------------------------------------------
kpis = [("5,53 Mio&nbsp;€", "Kapitalbedarf"), ("1.050", "Kunden bis Break-even"),
        ("Monat 46", "Break-even ab Start"), ("88 %", "Deckungsbeitrag"),
        ("2,15 Mio&nbsp;€", "ARR in Jahr 6")]
kpi_data = [[Paragraph(v, kpi_v) for v, _ in kpis], [Paragraph(l, kpi_l) for _, l in kpis]]
kw = W / 5
kpi = Table(kpi_data, colWidths=[kw] * 5)
kpi.setStyle(TableStyle([
    ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F7F9FC")),
    ("BOX", (0, 0), (-1, -1), 0.4, RULE),
    ("INNERGRID", (0, 0), (-1, -1), 0.4, RULE),
    ("TOPPADDING", (0, 0), (-1, 0), 4),
    ("BOTTOMPADDING", (0, 0), (-1, 0), 0),
    ("TOPPADDING", (0, 1), (-1, 1), 0),
    ("BOTTOMPADDING", (0, 1), (-1, 1), 4),
]))
story.append(kpi)

# --- Was --------------------------------------------------------------------
story.append(Paragraph("Was gebaut wird", h))
story.append(Paragraph(
    "Diagnose-, Service- und Codier-Software für freie Mehrmarken-Werkstätten, nativ auf Tablet und Smartphone, "
    "mit breiter VCI-/Dongle-Basis. Das Produkt ist nicht der Code, sondern der <b>Content</b>: geprüfte "
    "Werkstattfunktionen je Fahrzeugmodell, mit Vorher-/Nachher-Nachweis und revisionssicherem Audit-Log. "
    "Herstellerniveau (Schlüssel, Wegfahrsperre, Flashen) über lizenzierten Zugang und SERMI-Berechtigung — "
    "nicht über nachgebaute Algorithmen.", body))

story.append(Paragraph("Markt und Modell", h))
story.append(Paragraph(
    "Adressierbarer Markt DACH: <b>~19.000 freie Mehrmarken-Betriebe</b>, die heute 1.000–2.500 €/Jahr für "
    "Diagnosesoftware zahlen. Abo: Basis 49 € · Pro 129 € · Werkstatt Plus 249 € je Monat, Ziel-ARPA "
    "<b>150 €/Monat</b>. Der Content für 60 Modelle ist mit 0,54 Mio&nbsp;€ p.a. die zentrale Fixkostenposition — "
    "er muss von wenigen hundert zahlungswilligen Betrieben getragen werden, nicht von zweitausend preissensiblen.", body))

# --- Zahlen -----------------------------------------------------------------
story.append(Paragraph("Zahlen", h))
zahlen = [["Position", "Wert"],
          ["Entwicklung (18 Monate, 9,5 FTE)", "2,24 Mio&nbsp;€"],
          ["Content und Lizenzen (Stufe B, 60 Modelle)", "0,54 Mio&nbsp;€ p.a. — fix, nicht variabel"],
          ["ARPA-Ziel je Werkstatt", "150 €/Monat = 1.800 €/Jahr"],
          ["Deckungsbeitrag je Kunde", "1.588 €/Jahr (88 %)"],
          ["LTV / CAC / Amortisation je Kunde", "13.233 € / 700 € / <b>5,3 Monate</b>"],
          ["Break-even", "1.050 Kunden (5,5 % des SAM), Monat 46, ARR 1,89 Mio&nbsp;€"],
          ["Maximale Liquiditätslücke", "<b>5,53 Mio&nbsp;€</b>"]]
zahlen = [[Paragraph(c, ParagraphStyle("c", fontName="Helvetica-Bold" if i == 0 else "Helvetica",
                                       fontSize=7.1, leading=8.6, textColor=NAVY if i == 0 else colors.HexColor("#1a1a1a")))
           if not c.startswith("<b>") else Paragraph(c, ParagraphStyle("d", fontName="Helvetica", fontSize=7.1, leading=8.6))
           for c in row] for i, row in enumerate(zahlen)]
story.append(tbl(zahlen, [72 * mm, W - 72 * mm]))

# --- Kapitalverwendung ------------------------------------------------------
story.append(Paragraph("Kapitalverwendung", h))
kap = [["Tranche", "Betrag", "Zeitraum", "Inhalt", "Auslöser für die nächste Tranche"],
       ["T1", "1,8 Mio&nbsp;€", "M0–9", "Rechtsgutachten SERMI/J2534, 3 VCI-Partnerverträge, Diagnose-Core auf beiden Plattformen, Prüfstand",
        "Gutachten liegt vor, ein SDK-Vertrag signiert"],
       ["T2", "1,7 Mio&nbsp;€", "M9–20", "Codierung R2 mit Backup/Restore, Launch Stufe A/B, Fachhandelsvertrag",
        "100 zahlende Werkstätten, Churn < 2 %/Monat"],
       ["T3", "2,0 Mio&nbsp;€", "M20–46", "Skalierung auf 1.050 Kunden, SERMI-/Flashen-Modul", "300 Kunden, ARPA ≥ 140 €"],
       ["Summe", "5,5 Mio&nbsp;€", "", "", ""]]
kap_p = [[Paragraph(c, ParagraphStyle("k", fontName="Helvetica-Bold" if i in (0, 4) else "Helvetica",
                                      fontSize=7.1, leading=8.6, textColor=NAVY if i == 0 else colors.HexColor("#1a1a1a")))
          for c in row] for i, row in enumerate(kap)]
story.append(tbl(kap_p, [13 * mm, 15 * mm, 14 * mm, 78 * mm, W - 120 * mm]))

# --- Meilensteine -----------------------------------------------------------
story.append(Paragraph("Meilensteine", h))
ms = [["Zeit", "Meilenstein", "Nachweis"],
      ["M2", "Rechtslage und Partner geklärt", "Gutachten, 3 NDAs, 3–5 Pilotwerkstätten"],
      ["M6", "Diagnose-Core auf beiden Plattformen", "5 Service-Funktionen auf 5 Modellen, 2 VCIs zertifiziert"],
      ["M9", "Launch, 50 zahlende Werkstätten", "erster Umsatz, ARPA ≥ 140 €"],
      ["M15", "Codierung R2 produktiv", "300 Kunden, Restore in 100 % der Testfälle"],
      ["M24", "600 Kunden, ARR 0,9 Mio&nbsp;€", "Flashen/SERMI im Pilotbetrieb"],
      ["M46", "Break-even", "1.050 Kunden, monatlich positives Ergebnis"]]
ms_p = [[Paragraph(c, ParagraphStyle("m", fontName="Helvetica-Bold" if i == 0 or j == 0 else "Helvetica",
                                     fontSize=7.1, leading=8.6, textColor=NAVY if i == 0 else colors.HexColor("#1a1a1a")))
         for j, c in enumerate(row)] for i, row in enumerate(ms)]
story.append(tbl(ms_p, [13 * mm, 62 * mm, W - 75 * mm]))

# --- Bewertung --------------------------------------------------------------
story.append(Paragraph("Bewertungslogik", h))
story.append(Paragraph(
    "B2B-Software mit 88 % Bruttomarge und 12 % Abwanderung wird mit <b>4–8× ARR</b> bewertet. Bei 2,15&nbsp;Mio&nbsp;€ ARR "
    "(Jahr 6) ergibt das einen Unternehmenswert von <b>8,6–17,2&nbsp;Mio&nbsp;€</b>; ein Anteil von 35 % für "
    "5,5&nbsp;Mio&nbsp;€ ist damit 3,0–6,0&nbsp;Mio&nbsp;€ wert.", body))
story.append(Paragraph(
    "<b>Der ehrliche Teil:</b> Die volle Amortisation der aufgelaufenen Verluste liegt erst in <b>Jahr 9–10</b>. "
    "Für einen Finanzinvestor ist das unter der Venture-Schwelle. Der passendere Kapitalgeber ist ein "
    "<b>strategischer Partner</b> (Werkstattausrüster, Teilegroßhandel): dort ist der Content mit 0,54 Mio&nbsp;€ die "
    "einzige echte Zusatzkostenposition. Bei vorhandenem Vertrieb und Backoffice sinkt die nötige Kundenzahl von "
    "<b>~770 auf ~470</b> (ohne Entwicklungsamortisation) bzw. von 1.050 auf <b>~750</b> (mit). Der Wert liegt "
    "dann in Kundenbasis und Content-Asset, nicht im ARR-Multiple.", body))

# --- Risiken ----------------------------------------------------------------
story.append(Paragraph("Die drei Risiken, die den Plan kippen", h))
for i, t in enumerate([
    "<b>Zahlungsbereitschaft unter 150 €/Monat</b> — dann steigt der Break-even auf 1.358 Kunden (+30 %) und der Case trägt nicht.",
    "<b>Kein Fachhandels-/Kettenkanal</b> — Direktvertrieb erreicht in 72 Monaten keinen Break-even (8 Abschlüsse/Monat gegen 1.050 Kunden).",
    "<b>Rechtslage SERMI/J2534</b> — ohne belastbares Gutachten ist Stufe C nicht kalkulierbar und das Plus-Paket nicht verkäuflich.",
]):
    story.append(Paragraph(f"{i + 1}.&nbsp;&nbsp;{t}", body))

story.append(Spacer(1, 3))
story.append(Paragraph(
    "<b>In den ersten 8 Wochen zu beweisen:</b> Zahlungsbereitschaft in 10–15 Werkstattgesprächen · ein "
    "Fachhandelspartner mit ernsthafter Zusage · ein Rechtsgutachten zu SERMI und J2534.", body))

story.append(Spacer(1, 4))
story.append(Paragraph(
    "Alle Zahlen aus der Wirtschaftlichkeitsrechnung (Modell: model/wirtschaftlichkeit.py, Blatt 10_Empfehlung). "
    "Planungsrechnung auf Basis von Annahmen, keine geprüfte Finanzplanung; Steuern, Finanzierungskosten und "
    "Working Capital nicht enthalten. Keine Rechts- oder Anlageberatung.", small))

doc = SimpleDocTemplate(OUT, pagesize=A4,
                        leftMargin=14 * mm, rightMargin=14 * mm,
                        topMargin=11 * mm, bottomMargin=9 * mm,
                        title="Werkstatt-App — Investor One-Pager",
                        author="Wirtschaftlichkeitsrechnung Werkstatt-App",
                        subject="Investor Executive Summary")
doc.build(story)

raw = open(OUT, "rb").read()
pages = raw.count(b"/Type /Page") - raw.count(b"/Type /Pages")
print(f"geschrieben: {os.path.relpath(OUT)} · {len(raw) / 1024:.0f} KB · Seiten: {pages}")
