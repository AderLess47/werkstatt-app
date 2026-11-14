/*
 * test_rollout.c — Tests für die Rollout-Auswertung.
 *
 * Geprüft wird die Kette, die zentral läuft: Bericht einlesen, je Gerät
 * deduplizieren, zur Flottenaussage verdichten, Politik anwenden.
 */
#include "rollout.h"
#include "checker.h"

#include <stdio.h>
#include <string.h>

static int fails = 0;

#define CHECK(desc, cond)                        \
    do {                                         \
        if (cond) {                              \
            printf("PASS  %s\n", desc);          \
        } else {                                 \
            printf("FAIL  %s\n", desc);          \
            fails++;                             \
        }                                        \
    } while (0)

static int count_sub(const char *hay, const char *needle)
{
    int c = 0;
    while ((hay = strstr(hay, needle)) != NULL) {
        c++;
        hay += strlen(needle);
    }
    return c;
}

static void mk(ro_record *r, const char *site, const char *dev,
               const char *ts, int verdict, const char *fw)
{
    int i;
    memset(r, 0, sizeof(*r));
    snprintf(r->site, sizeof(r->site), "%s", site);
    snprintf(r->device, sizeof(r->device), "%s", dev);
    snprintf(r->timestamp, sizeof(r->timestamp), "%s", ts);
    snprintf(r->firmware, sizeof(r->firmware), "%s", fw);
    r->verdict = verdict;
    for (i = 0; i < RO_CHECK_IDS; i++) {
        r->status[i] = -1;
    }
}

int main(void)
{
    ro_record a, r, in[8], out[8];
    ro_summary s;
    char buf[4096];
    int n;

    /* --- Serialisierung und Rückweg --- */
    mk(&a, "Filiale Nord", "VCI-01", "2026-11-14T09:00:00Z", CHK_WARN, "1.3");
    a.status[0] = CHK_OK;
    a.status[2] = CHK_WARN;
    a.vbat_mv = 12200;
    a.resyncs = 2;
    a.crc_errors = 1;
    n = ro_to_json(&a, buf, sizeof(buf));
    CHECK("JSON erzeugt", n > 0);
    CHECK("JSON enthält Standort", strstr(buf, "Filiale Nord") != NULL);
    CHECK("JSON enthält Verdict warn", strstr(buf, "\"verdict\":\"warn\"") != NULL);

    memset(&r, 0, sizeof(r));
    CHECK("JSON wieder lesbar", ro_parse_line(buf, &r) == 0);
    CHECK("Standort übernommen", strcmp(r.site, "Filiale Nord") == 0);
    CHECK("Gerät übernommen", strcmp(r.device, "VCI-01") == 0);
    CHECK("Zeitstempel übernommen", strcmp(r.timestamp, "2026-11-14T09:00:00Z") == 0);
    CHECK("Firmware übernommen", strcmp(r.firmware, "1.3") == 0);
    CHECK("Verdict übernommen", r.verdict == CHK_WARN);
    CHECK("Zähler übernommen", r.resyncs == 2 && r.crc_errors == 1);
    CHECK("Prüfstatus übernommen", r.status[0] == CHK_OK && r.status[2] == CHK_WARN);
    CHECK("fehlende Prüfung bleibt -1", r.status[5] == -1);

    /* --- eine echte vci-check-Ausgabe --- */
    {
        const char *cli =
            "{\"device\":\"sim-vci\",\"firmware\":\"1.4\",\"verdict\":\"ok\","
            "\"resyncs\":0,\"crc_errors\":0,\"checks\":["
            "{\"id\":\"device\",\"status\":\"ok\",\"value\":0,\"unit\":\"\","
            "\"message\":\"VCI antwortet\",\"hint\":\"\"},"
            "{\"id\":\"vbat\",\"status\":\"ok\",\"value\":12480,\"unit\":\"mV\","
            "\"message\":\"Bordspannung 12.48 V\",\"hint\":\"\"}]}";
        memset(&r, 0, sizeof(r));
        CHECK("CLI-Ausgabe lesbar", ro_parse_line(cli, &r) == 0);
        CHECK("CLI-Verdict ok", r.verdict == CHK_OK);
        CHECK("CLI-Messwert übernommen", r.vbat_mv == 12480);
        CHECK("CLI ohne Standort bleibt leer", r.site[0] == '\0');
    }

    /* --- Müll wird abgewiesen --- */
    memset(&r, 0, sizeof(r));
    CHECK("leere Zeile abgewiesen", ro_parse_line("", &r) != 0);
    CHECK("JSON ohne Verdict abgewiesen",
          ro_parse_line("{\"site\":\"X\"}", &r) != 0);

    /* --- Dedupe: neuester Lauf gewinnt --- */
    mk(&in[0], "Nord", "VCI-01", "2026-11-14T08:00:00Z", CHK_FAIL, "1.3");
    mk(&in[1], "Nord", "VCI-01", "2026-11-14T11:00:00Z", CHK_OK, "1.5");
    mk(&in[2], "Süd", "VCI-02", "2026-11-14T09:00:00Z", CHK_WARN, "1.4");
    n = ro_dedupe(in, 3, out, 8);
    CHECK("Dedupe: 2 Geräte aus 3 Läufen", n == 2);
    CHECK("Dedupe: neuerer Lauf gewinnt", out[0].verdict == CHK_OK);
    CHECK("Dedupe: neuere Firmware gewinnt", strcmp(out[0].firmware, "1.5") == 0);

    /* --- Verdichtung --- */
    mk(&in[0], "Nord", "VCI-01", "", CHK_OK, "1.4");
    mk(&in[1], "Nord", "VCI-02", "", CHK_WARN, "1.4");
    mk(&in[2], "Süd", "VCI-03", "", CHK_FAIL, "1.4");
    mk(&in[3], "Süd", "VCI-04", "", CHK_OK, "1.4");
    in[1].status[2] = CHK_WARN;
    in[2].status[3] = CHK_FAIL;
    ro_summarize(in, 4, &s);
    CHECK("4 Geräte gezählt", s.devices == 4);
    CHECK("2 ok", s.ok == 2);
    CHECK("1 Warnung", s.warn == 1);
    CHECK("1 Fehler", s.fail == 1);
    CHECK("Bestandsquote 500 Promille", s.pass_permille == 500);
    CHECK("2 Standorte erkannt", s.sites == 2);
    CHECK("Handlungsbedarf: Bus zuerst", strcmp(s.hotspots[0].id, "bus") == 0);
    CHECK("Handlungsbedarf: Spannung danach", strcmp(s.hotspots[1].id, "vbat") == 0);

    /* --- Firmware-Alter numerisch, nicht alphabetisch --- */
    mk(&in[0], "Nord", "VCI-01", "", CHK_OK, "1.3");
    mk(&in[1], "Nord", "VCI-02", "", CHK_OK, "1.10");
    ro_summarize(in, 2, &s);
    CHECK("1.3 zählt als veraltet", s.firmware_old == 1);
    CHECK("1.10 gilt als aktuell", s.firmware_old == 1);

    /* --- Politik --- */
    mk(&in[0], "Nord", "VCI-01", "", CHK_OK, "1.4");
    mk(&in[1], "Nord", "VCI-02", "", CHK_FAIL, "1.4");
    ro_summarize(in, 2, &s);
    CHECK("Fehler blockiert Rollout", ro_policy_exit(&s, 0, 1) == 2);
    CHECK("Fehler mit --allow-fail nur Warnung", ro_policy_exit(&s, 0, 0) == 1);

    ro_summarize(in, 1, &s); /* nur der gesunde Datensatz */
    CHECK("alles ok gibt 0", ro_policy_exit(&s, 1000, 1) == 0);
    CHECK("Quote unter Schwelle blockiert", ro_policy_exit(&s, 1001, 1) == 2);

    mk(&in[0], "Nord", "VCI-01", "", CHK_WARN, "1.4");
    ro_summarize(in, 1, &s);
    CHECK("Warnung gibt 1", ro_policy_exit(&s, 0, 1) == 1);

    /* --- Zeitverlauf: ISO-Wochen --- */
    {
        int iy = 0;
        CHECK("2026-01-01 ist W01", ro_iso_week(2026, 1, 1, &iy) == 1 && iy == 2026);
        CHECK("2025-12-29 gehört zu 2026-W01", ro_iso_week(2025, 12, 29, &iy) == 1 && iy == 2026);
        CHECK("2026-11-14 ist W46", ro_iso_week(2026, 11, 14, &iy) == 46 && iy == 2026);
        CHECK("2027-01-01 gehört zu 2026-W53", ro_iso_week(2027, 1, 1, &iy) == 53 && iy == 2026);
        CHECK("31.02. wird abgewiesen", ro_iso_week(2026, 2, 31, &iy) == 0);
    }

    /* --- Zeitverlauf: Zustand der Flotte je Woche --- */
    {
        ro_timeline tl;
        ro_record w[6];

        mk(&w[0], "Nord", "VCI-01", "2026-11-02T10:00:00Z", CHK_OK, "1.4");
        mk(&w[1], "Nord", "VCI-02", "2026-11-03T10:00:00Z", CHK_OK, "1.4");
        mk(&w[2], "Süd", "VCI-03", "2026-11-09T10:00:00Z", CHK_FAIL, "1.4");
        w[2].status[2] = CHK_FAIL;
        mk(&w[3], "Süd", "VCI-03", "2026-11-16T10:00:00Z", CHK_OK, "1.4");
        mk(&w[4], "Nord", "VCI-04", "", CHK_OK, "1.4"); /* ohne Datum */

        ro_timeline_build(w, 5, &tl);
        CHECK("3 Wochen erkannt", tl.count == 3);
        CHECK("Datensatz ohne Datum übersprungen", tl.skipped == 1);
        CHECK("W45 vollständig erfasst", tl.items[0].week == 45 && tl.items[0].devices == 2 &&
                                        tl.items[0].pass_permille == 1000);
        CHECK("W46 mit Fehler", tl.items[1].week == 46 && tl.items[1].devices == 3 &&
                                  tl.items[1].fail == 1);
        CHECK("W46 Quote 666", tl.items[1].pass_permille == 666);
        CHECK("W45 ohne Vorwoche", tl.items[0].has_prev == 0);
        CHECK("Rückgang erkannt", tl.items[1].has_prev == 1 && tl.items[1].delta_permille == -334);
        CHECK("Nachprüfung bereinigt W47", tl.items[2].ok == 3 && tl.items[2].fail == 0);
        CHECK("Erholung erkannt", tl.items[2].delta_permille == 334);
        CHECK("Hauptproblem W46 ist vbat", strcmp(tl.items[1].top_id, "vbat") == 0);
        CHECK("kein Hauptproblem in W45", tl.items[0].top_id[0] == '\0');

        n = ro_timeline_md(&tl, buf, sizeof(buf));
        CHECK("Verlauf als Markdown", n > 0);
        CHECK("Verlauf nennt Woche", strstr(buf, "2026-W46") != NULL);
        CHECK("Verlauf nennt Hauptproblem", strstr(buf, "vbat (1)") != NULL);
        CHECK("Verlauf nennt übersprungenen Datensatz",
              strstr(buf, "ohne lesbaren Zeitstempel") != NULL);

        /* Sinkende Quote bei wachsender Gerätezahl ist Fortschritt, kein Rückschritt. */
        ro_timeline_build(w, 3, &tl);
        n = ro_timeline_md(&tl, buf, sizeof(buf));
        CHECK("Fortschritt statt Verschlechterung erklärt",
              strstr(buf, "Rollout-Fortschritt") != NULL);
    }

    /* --- Zeitverlauf: echter Rückschritt --- */
    {
        ro_timeline tl;
        ro_record w[2];

        mk(&w[0], "Nord", "VCI-01", "2026-11-02T10:00:00Z", CHK_OK, "1.4");
        mk(&w[1], "Nord", "VCI-01", "2026-11-09T10:00:00Z", CHK_WARN, "1.4");
        ro_timeline_build(w, 2, &tl);
        n = ro_timeline_md(&tl, buf, sizeof(buf));
        CHECK("Rückschritt bei gleicher Gerätezahl gemeldet",
              strstr(buf, "echter Rückschritt") != NULL);
        CHECK("Verlauf steckt im Gesamtbericht",
              (ro_summarize(w, 2, &s), ro_render_md(&s, w, 2, buf, sizeof(buf)) > 0) &&
              strstr(buf, "## Zeitverlauf") != NULL);
    }

    /* --- Diagramm für die Betreiber-Präsentation --- */
    {
        ro_timeline tl;
        ro_record w[2];

        mk(&w[0], "Nord", "VCI-01", "2026-11-02T10:00:00Z", CHK_OK, "1.4");
        mk(&w[1], "Nord", "VCI-02", "2026-11-09T10:00:00Z", CHK_FAIL, "1.4");
        ro_timeline_build(w, 2, &tl);
        n = ro_chart_svg(&tl, "Rollout \"Nord\" & <Süd>", "2 Wochen", 800, buf, sizeof(buf));
        CHECK("Diagramm erzeugt", n > 0);
        CHECK("Diagramm ist SVG", strstr(buf, "<svg") != NULL && strstr(buf, "</svg>") != NULL);
        CHECK("Diagramm nennt Wochen", strstr(buf, "W45") != NULL && strstr(buf, "W46") != NULL);
        CHECK("Diagramm nennt Quote mit Komma", strstr(buf, "100,0 %") != NULL);
        CHECK("Diagramm zeichnet zwei Punkte", count_sub(buf, "<circle") == 2);
        CHECK("Diagramm mit Fläche und Linie",
              strstr(buf, "<path") != NULL && strstr(buf, "<polyline") != NULL);
        CHECK("Titel wird escaped", strstr(buf, "&amp; &lt;Süd&gt;") != NULL &&
                                    strstr(buf, "&quot;Nord&quot;") != NULL);
        CHECK("Freigabeschwelle eingezeichnet", strstr(buf, "Freigabe 80,0 %") != NULL);
        CHECK("Woche unter Schwelle markiert", strstr(buf, "#c0392b") != NULL);
        CHECK("kleiner Puffer gemeldet", ro_chart_svg(&tl, "x", "y", 800, buf, 40) < 0);
        {
            ro_timeline leer;
            memset(&leer, 0, sizeof(leer));
            CHECK("ohne Wochen kein Diagramm", ro_chart_svg(&leer, "x", "y", -1, buf, 100) == 0);
        }
    }

    /* --- eine Woche ist kein Verlauf --- */
    {
        ro_timeline tl;
        ro_record w[2];
        mk(&w[0], "Nord", "VCI-01", "2026-11-02T10:00:00Z", CHK_OK, "1.4");
        mk(&w[1], "Nord", "VCI-02", "2026-11-03T10:00:00Z", CHK_OK, "1.4");
        ro_timeline_build(w, 2, &tl);
        CHECK("eine Woche ergibt keinen Verlauf", ro_timeline_md(&tl, buf, sizeof(buf)) == 0);
    }

    /* --- Berichte --- */
    mk(&in[0], "Filiale Nord", "VCI-01", "", CHK_OK, "1.4");
    mk(&in[1], "Filiale Süd", "VCI-02", "", CHK_FAIL, "1.3");
    in[1].status[3] = CHK_FAIL;
    ro_summarize(in, 2, &s);
    n = ro_render_md(&s, in, 2, buf, sizeof(buf));
    CHECK("Markdown erzeugt", n > 0);
    CHECK("Markdown nennt Standort", strstr(buf, "Filiale Nord") != NULL);
    CHECK("Markdown markiert blockierten Standort", strstr(buf, "blockiert") != NULL);
    CHECK("Markdown nennt Prüfung", strstr(buf, "| bus |") != NULL);
    CHECK("Markdown nennt Firmware-Hinweis", strstr(buf, "Mindest-Firmware") != NULL);

    n = ro_render_csv(in, 2, buf, sizeof(buf));
    CHECK("CSV erzeugt", n > 0);
    CHECK("CSV mit Kopfzeile", strstr(buf, "site;device;serial;timestamp") != NULL);

    /* --- Platzmangel wird erkannt, nicht überlaufen --- */
    CHECK("zu kleiner Puffer gemeldet", ro_render_md(&s, in, 2, buf, 20) < 0);

    if (fails == 0) {
        printf("\nAlle Rollout-Prüfungen bestanden.\n");
    } else {
        printf("\n%d Prüfung(en) fehlgeschlagen.\n", fails);
    }
    return fails == 0 ? 0 : 1;
}
