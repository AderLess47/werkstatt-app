/*
 * rollout.c — Rollout-Werkzeug für Werkstattketten.
 *
 * Ablauf in der Praxis:
 *   1. Plan anlegen: eine CSV mit Standort;Gerät;Seriennummer
 *   2. Vor Ort:    rollout run --site "Filiale Nord" --device VCI-01
 *   3. Zentral:    rollout import --plan plan.csv      (eingesammelte Berichte)
 *   4. Auswerten:  rollout report --in results.jsonl --min-pass 95
 *
 * Der Exit-Code von "report" ist die Rollout-Entscheidung: 0 = durch,
 * 1 = Warnungen, 2 = blockiert.
 */
/* Für gmtime_r/strftime unter -std=c99. */
#ifndef _POSIX_C_SOURCE
#  define _POSIX_C_SOURCE 200809L
#endif

#include "rollout.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern const usb_backend sim_backend;

#if defined(_WIN32)
extern const usb_backend winusb_backend;
#  define DEFAULT_BACKEND (&winusb_backend)
#else
#  define DEFAULT_BACKEND (&sim_backend)
#endif

#define MAX_LINE 4096

static void usage(void)
{
    printf(
        "Aufruf:\n"
        "  rollout run    --site NAME --device NAME [--serial SN] [--out FILE] [--sim]\n"
        "  rollout import --plan CSV [--out FILE]\n"
        "  rollout report --in FILE [--min-pass 0..100] [--format md|csv] [--allow-fail]\n"
        "  rollout chart  --in FILE [--out FILE.svg] [--title TEXT] [--threshold 0..100]\n"
        "\n"
        "  run     führt den VCI-Selbsttest aus und hängt das Ergebnis an die Sammeldatei\n"
        "  import  liest eingesammelte Prüfberichte laut Plan-CSV ein\n"
        "  report  wertet die Sammeldatei aus und schreibt den Bericht nach stdout\n"
        "  chart   zeichnet die Bestandsquote über die Wochen als SVG für die Präsentation\n"
        "\n"
        "Plan-CSV:  standort;geraet;seriennummer;pfad/zum/bericht.json\n"
        "Exit: 0 = durch, 1 = Warnungen, 2 = blockiert\n");
}

static void now_iso(char *out, size_t cap)
{
    time_t t = time(NULL);
    struct tm tmv;

#if defined(_WIN32)
    gmtime_s(&tmv, &t);
#else
    gmtime_r(&t, &tmv);
#endif
    if (strftime(out, cap, "%Y-%m-%dT%H:%M:%SZ", &tmv) == 0) {
        snprintf(out, cap, "1970-01-01T00:00:00Z");
    }
}

static int id_index(const char *id)
{
    int i;
    for (i = 0; i < RO_CHECK_IDS; i++) {
        if (strcmp(id, ro_check_ids[i]) == 0) {
            return i;
        }
    }
    return -1;
}

static int append_line(const char *path, const char *line)
{
    FILE *f = fopen(path, "a");
    if (f == NULL) {
        return -1;
    }
    fprintf(f, "%s\n", line);
    fclose(f);
    return 0;
}

static int load_records(const char *path, ro_record *recs, int max)
{
    FILE *f = fopen(path, "r");
    char line[MAX_LINE];
    int n = 0;

    if (f == NULL) {
        return -1;
    }
    while (n < max && fgets(line, sizeof(line), f) != NULL) {
        ro_record r;
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        if (len == 0) {
            continue;
        }
        if (ro_parse_line(line, &r) == 0) {
            recs[n++] = r;
        }
    }
    fclose(f);
    return n;
}

/* ------------------------------------------------------------------ run */

static int cmd_run(int argc, char **argv)
{
    const char *site = NULL;
    const char *device = NULL;
    const char *serial = "";
    const char *out = "rollout-results.jsonl";
    int use_sim = 0;
    int i, rc;
    chk_report rep;
    ro_record r;
    char json[MAX_LINE];

    for (i = 0; i < argc; i++) {
        if (strcmp(argv[i], "--site") == 0 && i + 1 < argc) {
            site = argv[++i];
        } else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
            device = argv[++i];
        } else if (strcmp(argv[i], "--serial") == 0 && i + 1 < argc) {
            serial = argv[++i];
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out = argv[++i];
        } else if (strcmp(argv[i], "--sim") == 0) {
            use_sim = 1;
        } else {
            fprintf(stderr, "unbekannte Option: %s\n", argv[i]);
            return 2;
        }
    }
    if (site == NULL || device == NULL) {
        fprintf(stderr, "--site und --device sind erforderlich\n");
        return 2;
    }

    tp_set_backend(use_sim ? &sim_backend : DEFAULT_BACKEND);
    rc = chk_run(&rep);

    memset(&r, 0, sizeof(r));
    snprintf(r.site, sizeof(r.site), "%s", site);
    snprintf(r.device, sizeof(r.device), "%s", device);
    snprintf(r.serial, sizeof(r.serial), "%s", serial);
    snprintf(r.firmware, sizeof(r.firmware), "%s", rep.firmware);
    now_iso(r.timestamp, sizeof(r.timestamp));
    r.verdict = rep.verdict;
    r.resyncs = rep.resyncs;
    r.crc_errors = rep.crc_errors;
    for (i = 0; i < RO_CHECK_IDS; i++) {
        r.status[i] = -1;
    }
    for (i = 0; i < rep.count; i++) {
        int idx = id_index(rep.items[i].id);
        if (idx >= 0) {
            r.status[idx] = rep.items[i].status;
            if (strcmp(rep.items[i].id, "vbat") == 0) {
                r.vbat_mv = rep.items[i].value;
            }
        }
    }

    if (ro_to_json(&r, json, sizeof(json)) < 0) {
        fprintf(stderr, "Datensatz konnte nicht serialisiert werden\n");
        return 2;
    }
    if (append_line(out, json) != 0) {
        fprintf(stderr, "Sammeldatei nicht schreibbar: %s\n", out);
        return 2;
    }
    printf("%s / %s: %s (%d Prüfungen) → %s\n", site, device,
           chk_verdict_name((chk_status)r.verdict), rep.count, out);
    return rc;
}

/* --------------------------------------------------------------- import */

static int cmd_import(int argc, char **argv)
{
    const char *plan = NULL;
    const char *out = "rollout-results.jsonl";
    char line[MAX_LINE];
    FILE *f;
    int i, imported = 0, failed = 0;

    for (i = 0; i < argc; i++) {
        if (strcmp(argv[i], "--plan") == 0 && i + 1 < argc) {
            plan = argv[++i];
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out = argv[++i];
        } else {
            fprintf(stderr, "unbekannte Option: %s\n", argv[i]);
            return 2;
        }
    }
    if (plan == NULL) {
        fprintf(stderr, "--plan ist erforderlich\n");
        return 2;
    }
    f = fopen(plan, "r");
    if (f == NULL) {
        fprintf(stderr, "Plan nicht lesbar: %s\n", plan);
        return 2;
    }
    while (fgets(line, sizeof(line), f) != NULL) {
        char *site, *device, *serial, *path, *p;
        size_t len = strlen(line);
        ro_record r;
        FILE *bf;
        char blob[MAX_LINE * 4];
        size_t got;

        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        if (len == 0 || line[0] == '#') {
            continue;
        }
        site = line;
        p = strchr(site, ';');
        if (p == NULL) { failed++; continue; }
        *p++ = '\0';
        device = p;
        p = strchr(device, ';');
        if (p == NULL) { failed++; continue; }
        *p++ = '\0';
        serial = p;
        p = strchr(serial, ';');
        if (p == NULL) { failed++; continue; }
        *p++ = '\0';
        path = p;

        bf = fopen(path, "r");
        if (bf == NULL) {
            fprintf(stderr, "  fehlt: %s\n", path);
            failed++;
            continue;
        }
        got = fread(blob, 1, sizeof(blob) - 1, bf);
        blob[got] = '\0';
        fclose(bf);

        if (ro_parse_line(blob, &r) != 0) {
            fprintf(stderr, "  unlesbar: %s\n", path);
            failed++;
            continue;
        }
        snprintf(r.site, sizeof(r.site), "%s", site);
        snprintf(r.device, sizeof(r.device), "%s", device);
        snprintf(r.serial, sizeof(r.serial), "%s", serial);
        if (r.timestamp[0] == '\0') {
            now_iso(r.timestamp, sizeof(r.timestamp));
        }
        {
            char json[MAX_LINE];
            if (ro_to_json(&r, json, sizeof(json)) < 0 || append_line(out, json) != 0) {
                failed++;
                continue;
            }
        }
        printf("  %s / %s: %s\n", site, device, chk_verdict_name((chk_status)r.verdict));
        imported++;
    }
    fclose(f);
    printf("%d übernommen, %d fehlgeschlagen → %s\n", imported, failed, out);
    return failed > 0 ? 1 : 0;
}

/* --------------------------------------------------------------- report */

static int cmd_report(int argc, char **argv)
{
    const char *in = "rollout-results.jsonl";
    int min_pass = 0;
    int format_csv = 0;
    int allow_fail = 0;
    int i, n, unique;
    static ro_record all[RO_MAX_RECORDS];
    static ro_record latest[RO_MAX_RECORDS];
    ro_summary sum;
    static char out[65536];

    for (i = 0; i < argc; i++) {
        if (strcmp(argv[i], "--in") == 0 && i + 1 < argc) {
            in = argv[++i];
        } else if (strcmp(argv[i], "--min-pass") == 0 && i + 1 < argc) {
            min_pass = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--format") == 0 && i + 1 < argc) {
            i++;
            format_csv = strcmp(argv[i], "csv") == 0;
        } else if (strcmp(argv[i], "--allow-fail") == 0) {
            allow_fail = 1;
        } else {
            fprintf(stderr, "unbekannte Option: %s\n", argv[i]);
            return 2;
        }
    }

    n = load_records(in, all, RO_MAX_RECORDS);
    if (n < 0) {
        fprintf(stderr, "Sammeldatei nicht lesbar: %s\n", in);
        return 2;
    }
    if (n == 0) {
        printf("Keine Datensätze in %s\n", in);
        return 2;
    }
    unique = ro_dedupe(all, n, latest, RO_MAX_RECORDS);
    ro_summarize(latest, unique, &sum);

    if (format_csv) {
        if (ro_render_csv(latest, unique, out, sizeof(out)) < 0) {
            fprintf(stderr, "Bericht zu groß\n");
            return 2;
        }
    } else {
        if (ro_render_md(&sum, latest, unique, out, sizeof(out)) < 0) {
            fprintf(stderr, "Bericht zu groß\n");
            return 2;
        }
    }
    printf("%s", out);
    return ro_policy_exit(&sum, min_pass * 10, !allow_fail);
}

/* ---------------------------------------------------------------- chart */

static int cmd_chart(int argc, char **argv)
{
    const char *in = "rollout-results.jsonl";
    const char *out = "rollout-quote.svg";
    const char *title = "Bestandsquote VCI";
    int threshold = -1;
    int i, n, len;
    static ro_record all[RO_MAX_RECORDS];
    static ro_timeline tl;
    static char svg[262144];
    char sub[160];
    FILE *f;

    for (i = 0; i < argc; i++) {
        if (strcmp(argv[i], "--in") == 0 && i + 1 < argc) {
            in = argv[++i];
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out = argv[++i];
        } else if (strcmp(argv[i], "--title") == 0 && i + 1 < argc) {
            title = argv[++i];
        } else if (strcmp(argv[i], "--threshold") == 0 && i + 1 < argc) {
            threshold = atoi(argv[++i]) * 10;
        } else {
            fprintf(stderr, "unbekannte Option: %s\n", argv[i]);
            return 2;
        }
    }

    n = load_records(in, all, RO_MAX_RECORDS);
    if (n <= 0) {
        fprintf(stderr, "keine Datensätze in %s\n", in);
        return 2;
    }
    ro_timeline_build(all, n, &tl);
    if (tl.count == 0) {
        fprintf(stderr, "kein Lauf mit lesbarem Zeitstempel\n");
        return 2;
    }
    if (tl.count < 2) {
        fprintf(stderr, "Hinweis: nur eine Woche erfasst — der Verlauf besteht aus einem Punkt.\n");
    }

    snprintf(sub, sizeof(sub), "%d-W%02d bis %d-W%02d · %d Geräte erfasst",
             tl.items[0].year, tl.items[0].week,
             tl.items[tl.count - 1].year, tl.items[tl.count - 1].week,
             tl.items[tl.count - 1].devices);

    len = ro_chart_svg(&tl, title, sub, threshold, svg, sizeof(svg));
    if (len <= 0) {
        fprintf(stderr, "Diagramm konnte nicht erzeugt werden\n");
        return 2;
    }
    f = fopen(out, "w");
    if (f == NULL) {
        fprintf(stderr, "nicht schreibbar: %s\n", out);
        return 2;
    }
    fwrite(svg, 1, (size_t)len, f);
    fclose(f);
    printf("Diagramm: %s (%d Wochen, %d Geräte)\n", out, tl.count,
           tl.items[tl.count - 1].devices);
    return 0;
}

/* ----------------------------------------------------------------- main */

int main(int argc, char **argv)
{
    if (argc < 2) {
        usage();
        return 2;
    }
    if (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0) {
        usage();
        return 0;
    }
    if (strcmp(argv[1], "run") == 0) {
        return cmd_run(argc - 2, argv + 2);
    }
    if (strcmp(argv[1], "import") == 0) {
        return cmd_import(argc - 2, argv + 2);
    }
    if (strcmp(argv[1], "report") == 0) {
        return cmd_report(argc - 2, argv + 2);
    }
    if (strcmp(argv[1], "chart") == 0) {
        return cmd_chart(argc - 2, argv + 2);
    }
    usage();
    return 2;
}
