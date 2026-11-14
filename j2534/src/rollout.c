/*
 * rollout.c — Einlesen, Verdichten und Bewerten von Rollout-Ergebnissen.
 *
 * Der Parser ist bewusst kein allgemeiner JSON-Parser, sondern liest genau das
 * Format, das der Checker schreibt. Ein allgemeiner Parser wäre mehr Code und
 * mehr Angriffsfläche für Fehler, ohne hier etwas zu gewinnen.
 */
#include "rollout.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

const char *ro_check_ids[RO_CHECK_IDS] = {
    "device", "firmware", "vbat", "bus", "isotp", "line"
};

/* --------------------------------------------------------------- Parser */

static const char *skip_ws(const char *p)
{
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') {
        p++;
    }
    return p;
}

/* Liest "key":"value" (value wird kopiert, ohne Escapes — Format ist unseres). */
static int json_str(const char *js, const char *key, char *out, size_t cap)
{
    char pat[48];
    const char *p;
    size_t i = 0;

    snprintf(pat, sizeof(pat), "\"%s\":\"", key);
    p = strstr(js, pat);
    if (p == NULL) {
        return 0;
    }
    p += strlen(pat);
    while (p[i] && p[i] != '"' && i + 1 < cap) {
        out[i] = p[i];
        i++;
    }
    out[i] = '\0';
    return 1;
}

/* Liest "key":1234 (ohne Anführungszeichen). */
static int json_num(const char *js, const char *key, long *out)
{
    char pat[48];
    const char *p;

    snprintf(pat, sizeof(pat), "\"%s\":", key);
    p = strstr(js, pat);
    if (p == NULL) {
        return 0;
    }
    p = skip_ws(p + strlen(pat));
    if (*p == '"') { /* Zahl als String */
        p++;
    }
    {
        long v = 0;
        int any = 0;
        while (*p >= '0' && *p <= '9') {
            v = v * 10 + (*p - '0');
            p++;
            any = 1;
        }
        if (!any) {
            return 0;
        }
        *out = v;
    }
    return 1;
}

static int status_from_name(const char *name)
{
    if (strcmp(name, "ok") == 0) {
        return CHK_OK;
    }
    if (strcmp(name, "warn") == 0) {
        return CHK_WARN;
    }
    if (strcmp(name, "fail") == 0) {
        return CHK_FAIL;
    }
    if (strcmp(name, "skip") == 0) {
        return CHK_SKIP;
    }
    return -1;
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

int ro_parse_line(const char *line, ro_record *r)
{
    const char *p;
    int i;

    if (line == NULL || r == NULL) {
        return -1;
    }
    memset(r, 0, sizeof(*r));
    for (i = 0; i < RO_CHECK_IDS; i++) {
        r->status[i] = -1;
    }
    {
        char verdict[16];
        if (!json_str(line, "verdict", verdict, sizeof(verdict))) {
            return -1;
        }
        r->verdict = status_from_name(verdict);
        if (r->verdict < 0) {
            return -1;
        }
    }
    /* site/device/serial sind optional (bei vci-check-Ausgaben nicht vorhanden) */
    json_str(line, "site", r->site, sizeof(r->site));
    json_str(line, "device", r->device, sizeof(r->device));
    json_str(line, "serial", r->serial, sizeof(r->serial));
    json_str(line, "timestamp", r->timestamp, sizeof(r->timestamp));
    json_str(line, "firmware", r->firmware, sizeof(r->firmware));

    {
        long v = 0;
        if (json_num(line, "resyncs", &v)) {
            r->resyncs = (unsigned)v;
        }
        if (json_num(line, "crc_errors", &v)) {
            r->crc_errors = (unsigned)v;
        }
    }

    /* Prüfungen: Paare aus "id":"..." und dem folgenden "status":"..." */
    p = line;
    while ((p = strstr(p, "\"id\":\"")) != NULL) {
        char id[24];
        char st[16];
        size_t n = 0;
        const char *q;

        p += 6;
        while (*p && *p != '"' && n + 1 < sizeof(id)) {
            id[n++] = *p++;
        }
        id[n] = '\0';
        q = strstr(p, "\"status\":\"");
        if (q == NULL) {
            break;
        }
        q += 10;
        n = 0;
        while (*q && *q != '"' && n + 1 < sizeof(st)) {
            st[n++] = *q++;
        }
        st[n] = '\0';
        {
            int idx = id_index(id);
            int status = status_from_name(st);
            if (idx >= 0 && status >= 0) {
                r->status[idx] = status;
                if (strcmp(id, "vbat") == 0) {
                    long v = 0;
                    if (json_num(q, "value", &v)) {
                        r->vbat_mv = v;
                    }
                }
            }
        }
        p = q;
    }
    return 0;
}

int ro_to_json(const ro_record *r, char *buf, size_t cap)
{
    int n;
    int i, first = 1;

    if (r == NULL || buf == NULL || cap == 0) {
        return -1;
    }
    n = snprintf(buf, cap,
                 "{\"site\":\"%s\",\"device\":\"%s\",\"serial\":\"%s\",\"timestamp\":\"%s\","
                 "\"firmware\":\"%s\",\"verdict\":\"%s\",\"resyncs\":%u,\"crc_errors\":%u,"
                 "\"checks\":[",
                 r->site, r->device, r->serial, r->timestamp, r->firmware,
                 chk_status_name((chk_status)r->verdict), r->resyncs, r->crc_errors);
    if (n < 0 || (size_t)n >= cap) {
        return -1;
    }
    for (i = 0; i < RO_CHECK_IDS; i++) {
        int m;
        if (r->status[i] < 0) {
            continue;
        }
        m = snprintf(buf + n, cap - (size_t)n, "%s{\"id\":\"%s\",\"status\":\"%s\"}",
                     first ? "" : ",", ro_check_ids[i],
                     chk_status_name((chk_status)r->status[i]));
        if (m < 0 || (size_t)m >= cap - (size_t)n) {
            return -1;
        }
        n += m;
        first = 0;
    }
    if ((size_t)n + 3 >= cap) {
        return -1;
    }
    buf[n++] = ']';
    buf[n++] = '}';
    buf[n] = '\0';
    return n;
}

/* --------------------------------------------------------------- Dedupe */

int ro_dedupe(const ro_record *in, int n, ro_record *out, int max)
{
    int i, j, count = 0;

    for (i = 0; i < n && count < max; i++) {
        int found = -1;
        for (j = 0; j < count; j++) {
            if (strcmp(out[j].site, in[i].site) == 0 &&
                strcmp(out[j].device, in[i].device) == 0) {
                found = j;
                break;
            }
        }
        if (found < 0) {
            out[count++] = in[i];
        } else if (strcmp(in[i].timestamp, out[found].timestamp) > 0) {
            out[found] = in[i]; /* neuerer Lauf gewinnt */
        }
    }
    return count;
}

/* ---------------------------------------------------------- Auswertung */

static int site_slot(ro_summary *s, const char *name)
{
    int i;
    for (i = 0; i < s->sites; i++) {
        if (strcmp(s->site_name[i], name) == 0) {
            return i;
        }
    }
    if (s->sites >= RO_MAX_SITES) {
        return -1;
    }
    snprintf(s->site_name[s->sites], sizeof(s->site_name[0]), "%s", name);
    s->site_ok[s->sites] = 0;
    s->site_warn[s->sites] = 0;
    s->site_fail[s->sites] = 0;
    return s->sites++;
}

/* Versionsvergleich numerisch: "1.10" ist neuer als "1.4". */
static int version_lt(const char *fw, int maj, int min)
{
    int a, b = 0;
    char *end = NULL;

    if (fw == NULL || fw[0] < '0' || fw[0] > '9') {
        return 0;
    }
    a = (int)strtol(fw, &end, 10);
    if (end != NULL && *end == '.') {
        b = (int)strtol(end + 1, NULL, 10);
    }
    return (a < maj) || (a == maj && b < min);
}

void ro_summarize(const ro_record *recs, int n, ro_summary *s)
{
    int i, k;

    memset(s, 0, sizeof(*s));
    s->devices = n;
    for (k = 0; k < RO_CHECK_IDS; k++) {
        s->hotspots[k].id = ro_check_ids[k];
    }

    for (i = 0; i < n; i++) {
        const ro_record *r = &recs[i];
        int slot = site_slot(s, r->site[0] ? r->site : "(ohne Standort)");

        if (r->verdict == CHK_OK) {
            s->ok++;
            if (slot >= 0) {
                s->site_ok[slot]++;
            }
        } else if (r->verdict == CHK_FAIL) {
            s->fail++;
            if (slot >= 0) {
                s->site_fail[slot]++;
            }
        } else {
            s->warn++;
            if (slot >= 0) {
                s->site_warn[slot]++;
            }
        }
        if (version_lt(r->firmware, 1, 4)) {
            s->firmware_old++;
        }
        for (k = 0; k < RO_CHECK_IDS; k++) {
            switch (r->status[k]) {
            case CHK_OK:   s->hotspots[k].ok++;   break;
            case CHK_WARN: s->hotspots[k].warn++; break;
            case CHK_FAIL: s->hotspots[k].fail++; break;
            case CHK_SKIP: s->hotspots[k].skip++; break;
            default: break;
            }
        }
    }
    s->pass_permille = n > 0 ? (s->ok * 1000) / n : 0;

    /* Handlungsbedarf: meiste Fehler, dann meiste Warnungen (Einfügesortierung) */
    for (i = 1; i < RO_CHECK_IDS; i++) {
        ro_hotspot key = s->hotspots[i];
        int j = i - 1;
        while (j >= 0 &&
               (s->hotspots[j].fail * 2 + s->hotspots[j].warn) < (key.fail * 2 + key.warn)) {
            s->hotspots[j + 1] = s->hotspots[j];
            j--;
        }
        s->hotspots[j + 1] = key;
    }
}

/* ------------------------------------------------------------- Ausgabe */

static int app(char *buf, size_t cap, size_t *used, const char *fmt, ...)
{
    va_list ap;
    int n;

    if (*used >= cap) {
        return -1;
    }
    va_start(ap, fmt);
    n = vsnprintf(buf + *used, cap - *used, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= cap - *used) {
        return -1;
    }
    *used += (size_t)n;
    return 0;
}

/* -------------------------------------------------------- Zeitverlauf */

static int is_leap_y(int y)
{
    return (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0);
}

/* Tage seit 1970-01-01 (Howard Hinnant, proleptischer gregorianischer Kalender). */
static long days_from_civil(int y, int m, int d)
{
    long yy = (long)y - (m <= 2 ? 1 : 0);
    long era = (yy >= 0 ? yy : yy - 399) / 400;
    long yoe = yy - era * 400;
    long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + (long)d - 1;
    long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

static int iso_weeks_in_year(int y)
{
    int wd = (int)(((days_from_civil(y, 1, 1) + 4) % 7 + 7) % 7); /* 0=So */
    int wd_mon = (wd == 0) ? 7 : wd;

    if (wd_mon == 4) {          /* 1. Januar ist Donnerstag */
        return 53;
    }
    if (is_leap_y(y) && wd_mon == 3) { /* Schaltjahr, 1. Januar ist Mittwoch */
        return 53;
    }
    return 52;
}

int ro_iso_week(int y, int m, int d, int *iso_year)
{
    static const int mdays[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int doy = d;
    int i, wd, wd_mon, week, yr = y;

    if (y < 1970 || m < 1 || m > 12 || d < 1 || d > 31) {
        return 0;
    }
    for (i = 0; i < m - 1; i++) {
        doy += mdays[i];
    }
    if (m > 2 && is_leap_y(y)) {
        doy++;
    }
    if (d > mdays[m - 1] + (m == 2 && is_leap_y(y) ? 1 : 0)) {
        return 0;
    }

    wd = (int)(((days_from_civil(y, m, d) + 4) % 7 + 7) % 7); /* 0=So */
    wd_mon = (wd == 0) ? 7 : wd;
    week = (doy - wd_mon + 10) / 7;

    if (week < 1) {
        yr = y - 1;
        week = iso_weeks_in_year(yr);
    } else if (week > iso_weeks_in_year(y)) {
        yr = y + 1;
        week = 1;
    }
    if (iso_year != NULL) {
        *iso_year = yr;
    }
    return week;
}

/* Sortierschlüssel JJJJWW; 0 wenn der Zeitstempel unbrauchbar ist. */
static int rec_key(const ro_record *r)
{
    int y = 0, m = 0, d = 0, iso_y = 0, wk;

    if (r->timestamp[0] == '\0') {
        return 0;
    }
    if (sscanf(r->timestamp, "%d-%d-%d", &y, &m, &d) != 3) {
        return 0;
    }
    wk = ro_iso_week(y, m, d, &iso_y);
    if (wk <= 0) {
        return 0;
    }
    return iso_y * 100 + wk;
}

void ro_timeline_build(const ro_record *recs, int n, ro_timeline *t)
{
    int keys[64];
    int nk = 0;
    const ro_record *firsts[RO_MAX_RECORDS];
    int ndev = 0;
    int i, j, k, start;

    memset(t, 0, sizeof(*t));
    if (recs == NULL || n <= 0) {
        return;
    }

    for (i = 0; i < n; i++) {
        int key = rec_key(&recs[i]);
        int seen = 0;
        if (key == 0) {
            t->skipped++;
            continue;
        }
        for (j = 0; j < nk; j++) {
            if (keys[j] == key) {
                seen = 1;
                break;
            }
        }
        if (!seen && nk < (int)(sizeof(keys) / sizeof(keys[0]))) {
            /* sortiert einfügen */
            int p = nk;
            while (p > 0 && keys[p - 1] > key) {
                keys[p] = keys[p - 1];
                p--;
            }
            keys[p] = key;
            nk++;
        }
    }

    if (nk == 0) {
        return;
    }
    /* Bei sehr langer Historie nur die letzten Wochen zeigen. */
    start = (nk > RO_MAX_WEEKS) ? nk - RO_MAX_WEEKS : 0;

    /* Erstes Vorkommen je Standort+Gerät reicht als Repräsentant. */
    for (i = 0; i < n && ndev < RO_MAX_RECORDS; i++) {
        int seen = 0;
        for (j = 0; j < ndev; j++) {
            if (strcmp(firsts[j]->site, recs[i].site) == 0 &&
                strcmp(firsts[j]->device, recs[i].device) == 0) {
                seen = 1;
                break;
            }
        }
        if (!seen) {
            firsts[ndev++] = &recs[i];
        }
    }

    for (k = start; k < nk; k++) {
        ro_week *w = &t->items[t->count];
        int cf[RO_CHECK_IDS];
        int cw[RO_CHECK_IDS];
        int best_id = -1, best_score = 0;

        memset(cf, 0, sizeof(cf));
        memset(cw, 0, sizeof(cw));
        w->year = keys[k] / 100;
        w->week = keys[k] % 100;

        for (j = 0; j < ndev; j++) {
            const ro_record *best = NULL;
            for (i = 0; i < n; i++) {
                int rk;
                if (strcmp(recs[i].site, firsts[j]->site) != 0 ||
                    strcmp(recs[i].device, firsts[j]->device) != 0) {
                    continue;
                }
                rk = rec_key(&recs[i]);
                if (rk == 0 || rk > keys[k]) {
                    continue; /* noch nicht erfasst oder erst später geprüft */
                }
                if (best == NULL || strcmp(recs[i].timestamp, best->timestamp) > 0) {
                    best = &recs[i];
                }
            }
            if (best == NULL) {
                continue;
            }
            w->devices++;
            if (best->verdict == CHK_OK) {
                w->ok++;
            } else if (best->verdict == CHK_FAIL) {
                w->fail++;
            } else {
                w->warn++;
            }
            for (i = 0; i < RO_CHECK_IDS; i++) {
                if (best->status[i] == CHK_FAIL) {
                    cf[i]++;
                } else if (best->status[i] == CHK_WARN) {
                    cw[i]++;
                }
            }
        }

        w->pass_permille = w->devices > 0 ? (w->ok * 1000) / w->devices : 0;
        for (i = 0; i < RO_CHECK_IDS; i++) {
            int score = cf[i] * 2 + cw[i];
            if (score > best_score) {
                best_score = score;
                best_id = i;
            }
        }
        if (best_id >= 0) {
            snprintf(w->top_id, sizeof(w->top_id), "%s", ro_check_ids[best_id]);
            w->top_count = cf[best_id] + cw[best_id];
        }
        if (t->count > 0) {
            w->has_prev = 1;
            w->delta_permille = w->pass_permille - t->items[t->count - 1].pass_permille;
        }
        t->count++;
    }
}

int ro_timeline_md(const ro_timeline *t, char *buf, size_t cap)
{
    size_t used = 0;
    int i;

    if (t == NULL || buf == NULL) {
        return -1;
    }
    if (t->count < 2) {
        return 0; /* eine Woche ist kein Verlauf */
    }
    if (app(buf, cap, &used,
            "## Zeitverlauf\n\n"
            "| Woche | Geräte | ok | Warnung | Fehler | Quote | ± Vorwoche | Hauptproblem |\n"
            "|---|---|---|---|---|---|---|---|\n") != 0) {
        return -1;
    }
    for (i = 0; i < t->count; i++) {
        const ro_week *w = &t->items[i];
        char delta[16];
        char prob[40];

        if (!w->has_prev) {
            snprintf(delta, sizeof(delta), "—");
        } else if (w->delta_permille == 0) {
            snprintf(delta, sizeof(delta), "0");
        } else {
            snprintf(delta, sizeof(delta), "%+.1f", (double)w->delta_permille / 10.0);
        }
        if (w->top_id[0] != '\0') {
            snprintf(prob, sizeof(prob), "%s (%d)", w->top_id, w->top_count);
        } else {
            snprintf(prob, sizeof(prob), "—");
        }
        if (app(buf, cap, &used, "| %d-W%02d | %d | %d | %d | %d | %.1f %% | %s | %s |\n",
                w->year, w->week, w->devices, w->ok, w->warn, w->fail,
                (double)w->pass_permille / 10.0, delta, prob) != 0) {
            return -1;
        }
    }
    used += 0;

    {
        const ro_week *f = &t->items[0];
        const ro_week *l = &t->items[t->count - 1];
        if (l->pass_permille < f->pass_permille && l->devices > f->devices) {
            if (app(buf, cap, &used,
                    "\n**Hinweis:** Die Quote ist von %.1f %% auf %.1f %% gesunken, während die Zahl "
                    "erfasster Geräte von %d auf %d gestiegen ist. Das ist der Rollout-Fortschritt, "
                    "nicht zwingend eine Verschlechterung — entscheidend ist die Quote bei gleicher "
                    "Gerätezahl.\n\n",
                    (double)f->pass_permille / 10.0, (double)l->pass_permille / 10.0,
                    f->devices, l->devices) != 0) {
                return -1;
            }
        } else if (l->pass_permille < f->pass_permille && l->devices == f->devices) {
            if (app(buf, cap, &used,
                    "\n**Hinweis:** Bei gleichbleibender Gerätezahl ist die Quote gesunken — das ist "
                    "ein echter Rückschritt und gehört vor die nächste Freigabe.\n\n") != 0) {
                return -1;
            }
        } else if (t->count > 0 && used > 0) {
            if (app(buf, cap, &used, "\n") != 0) {
                return -1;
            }
        }
    }
    if (t->skipped > 0) {
        if (app(buf, cap, &used,
                "_%d Datensatz/Datensätze ohne lesbaren Zeitstempel sind im Verlauf nicht enthalten._\n\n",
                t->skipped) != 0) {
            return -1;
        }
    }
    return (int)used;
}

/* ----------------------------------------------------------- Diagramm */

#define CH_W      960
#define CH_H      540
#define CH_LEFT   110
#define CH_RIGHT  900
#define CH_TOP    200
#define CH_BOTTOM 400

static void svg_escape(const char *in, char *out, size_t cap)
{
    size_t o = 0;

    if (in == NULL) {
        in = "";
    }
    for (; *in != '\0' && o + 7 < cap; in++) {
        const char *rep = NULL;
        switch (*in) {
        case '&': rep = "&amp;";  break;
        case '<': rep = "&lt;";   break;
        case '>': rep = "&gt;";   break;
        case '"': rep = "&quot;"; break;
        default: break;
        }
        if (rep != NULL) {
            size_t l = strlen(rep);
            memcpy(out + o, rep, l);
            o += l;
        } else {
            out[o++] = *in;
        }
    }
    out[o] = '\0';
}

/* Promille als deutsche Prozentangabe: 714 -> "71,4 %". */
static void fmt_pct(int permille, char *out, size_t cap)
{
    snprintf(out, cap, "%d,%d %%", permille / 10, permille % 10);
}

static int chart_x(int i, int count)
{
    int span = CH_RIGHT - CH_LEFT;
    if (count <= 1) {
        return CH_LEFT + span / 2;
    }
    return CH_LEFT + (span * i) / (count - 1);
}

static int chart_y(int permille)
{
    int span = CH_BOTTOM - CH_TOP;
    if (permille < 0) {
        permille = 0;
    }
    if (permille > 1000) {
        permille = 1000;
    }
    return CH_BOTTOM - (span * permille) / 1000;
}

int ro_chart_svg(const ro_timeline *t, const char *title, const char *subtitle,
                 int threshold_permille, char *buf, size_t cap)
{
    size_t used = 0;
    char esc_title[200];
    char esc_sub[200];
    int i;

    if (t == NULL || buf == NULL) {
        return -1;
    }
    if (t->count == 0) {
        return 0;
    }
    svg_escape(title != NULL && title[0] ? title : "Bestandsquote VCI", esc_title, sizeof(esc_title));
    svg_escape(subtitle, esc_sub, sizeof(esc_sub));

    if (app(buf, cap, &used,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" "
            "viewBox=\"0 0 %d %d\" font-family=\"Helvetica, Arial, sans-serif\">\n"
            "<rect width=\"100%%\" height=\"100%%\" fill=\"#ffffff\"/>\n"
            "<text x=\"64\" y=\"64\" font-size=\"26\" font-weight=\"bold\" fill=\"#14375c\">%s</text>\n"
            "<text x=\"64\" y=\"92\" font-size=\"14\" fill=\"#5b6b7c\">%s</text>\n",
            CH_W, CH_H, CH_W, CH_H, esc_title, esc_sub) != 0) {
        return -1;
    }

    /* Gitter und Prozentachse */
    for (i = 0; i <= 100; i += 25) {
        int y = chart_y(i * 10);
        if (app(buf, cap, &used,
                "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"1\"/>\n",
                CH_LEFT, y, CH_RIGHT, y, i == 0 ? "#8a97a5" : "#e3e8ee") != 0) {
            return -1;
        }
        if (app(buf, cap, &used,
                "<text x=\"%d\" y=\"%d\" font-size=\"13\" fill=\"#5b6b7c\" text-anchor=\"end\">%d %%</text>\n",
                CH_LEFT - 14, y + 5, i) != 0) {
            return -1;
        }
    }

    /* Freigabeschwelle als Linie, Beschriftung in der Legende oben rechts.
       Direkt an der Linie kollidiert sie mit dem Wert der letzten Woche. */
    if (threshold_permille >= 0 && threshold_permille <= 1000) {
        int ty = chart_y(threshold_permille);
        char th[16];
        fmt_pct(threshold_permille, th, sizeof(th));
        if (app(buf, cap, &used,
                "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#b8860b\" "
                "stroke-width=\"2\" stroke-dasharray=\"7 5\"/>\n"
                "<line x1=\"%d\" y1=\"124\" x2=\"%d\" y2=\"124\" stroke=\"#b8860b\" "
                "stroke-width=\"2\" stroke-dasharray=\"7 5\"/>\n"
                "<text x=\"%d\" y=\"128\" font-size=\"12\" fill=\"#8a6508\" text-anchor=\"end\">"
                "Freigabe %s</text>\n",
                CH_LEFT, ty, CH_RIGHT, ty, CH_RIGHT - 210, CH_RIGHT - 180, CH_RIGHT - 225, th) != 0) {
            return -1;
        }
    }

    /* Fläche unter der Kurve */
    for (i = 0; i < t->count; i++) {
        int x = chart_x(i, t->count);
        int y = chart_y(t->items[i].pass_permille);
        if (i == 0) {
            if (app(buf, cap, &used, "<path d=\"M %d,%d", x, CH_BOTTOM) != 0) {
                return -1;
            }
        }
        if (app(buf, cap, &used, " L %d,%d", x, y) != 0) {
            return -1;
        }
    }
    if (app(buf, cap, &used, " L %d,%d Z\" fill=\"#dce9f5\"/>\n",
            chart_x(t->count - 1, t->count), CH_BOTTOM) != 0) {
        return -1;
    }

    /* Linie */
    for (i = 0; i < t->count; i++) {
        if (app(buf, cap, &used, "%s%d,%d", i == 0 ? "<polyline points=\"" : " ",
                chart_x(i, t->count), chart_y(t->items[i].pass_permille)) != 0) {
            return -1;
        }
    }
    if (app(buf, cap, &used,
            "\" fill=\"none\" stroke=\"#1f4e79\" stroke-width=\"3\" stroke-linejoin=\"round\"/>\n") != 0) {
        return -1;
    }

    /* Punkte, Werte und Wochen */
    for (i = 0; i < t->count; i++) {
        const ro_week *w = &t->items[i];
        int x = chart_x(i, t->count);
        int y = chart_y(w->pass_permille);
        const char *dot = (threshold_permille >= 0 && w->pass_permille < threshold_permille)
                          ? "#c0392b" : "#1f4e79";
        const char *anc = "middle";
        int tx = x;
        char val[16];

        /* Randpunkte innen verankern, damit die Beschriftung nicht aus dem Bild läuft. */
        if (t->count > 1 && i == 0) {
            anc = "start";
            tx = x - 6;
        } else if (t->count > 1 && i == t->count - 1) {
            anc = "end";
            tx = x + 6;
        }

        fmt_pct(w->pass_permille, val, sizeof(val));
        if (app(buf, cap, &used,
                "<circle cx=\"%d\" cy=\"%d\" r=\"6\" fill=\"%s\" stroke=\"#ffffff\" stroke-width=\"2\"/>\n",
                x, y, dot) != 0) {
            return -1;
        }
        /* Bei vielen Wochen würden sich die Werte gegenseitig überdecken.
           Liegt der Punkt an der Schwellenlinie, wandert der Wert darunter. */
        {
            int ly = y - 14;
            if (threshold_permille >= 0 && threshold_permille <= 1000) {
                int ty = chart_y(threshold_permille);
                if (y > ty ? (y - ty) < 22 : (ty - y) < 22) {
                    ly = y + 26;
                }
            }
            if (t->count <= 8 &&
                app(buf, cap, &used,
                    "<text x=\"%d\" y=\"%d\" font-size=\"14\" font-weight=\"bold\" fill=\"%s\" "
                    "text-anchor=\"%s\">%s</text>\n",
                    tx, ly, dot, anc, val) != 0) {
                return -1;
            }
        }
        if (app(buf, cap, &used,
                "<text x=\"%d\" y=\"%d\" font-size=\"13\" fill=\"#3c4a58\" text-anchor=\"middle\">W%02d</text>\n"
                "<text x=\"%d\" y=\"%d\" font-size=\"11\" fill=\"#8a97a5\" text-anchor=\"middle\">%d Ger.</text>\n",
                x, CH_BOTTOM + 22, w->week, x, CH_BOTTOM + 38, w->devices) != 0) {
            return -1;
        }
    }

    /* Fußzeile */
    {
        const ro_week *l = &t->items[t->count - 1];
        if (app(buf, cap, &used,
                "<text x=\"%d\" y=\"%d\" font-size=\"11\" fill=\"#8a97a5\">"
                "Quelle: VCI-Rollout-Bericht · Stand %d-W%02d · %d Geräte erfasst</text>\n",
                CH_LEFT, CH_H - 28, l->year, l->week, l->devices) != 0) {
            return -1;
        }
    }
    if (app(buf, cap, &used, "</svg>\n") != 0) {
        return -1;
    }
    return (int)used;
}

int ro_render_md(const ro_summary *s, const ro_record *recs, int n, char *buf, size_t cap)
{
    size_t used = 0;
    int i, k;

    if (s == NULL || buf == NULL) {
        return -1;
    }
    if (app(buf, cap, &used, "# Rollout-Bericht VCI\n\n") != 0) {
        return -1;
    }
    if (app(buf, cap, &used,
            "**Geräte:** %d · **ok:** %d · **Warnung:** %d · **Fehler:** %d · "
            "**Bestandsquote:** %.1f %%\n\n",
            s->devices, s->ok, s->warn, s->fail, (double)s->pass_permille / 10.0) != 0) {
        return -1;
    }
    if (app(buf, cap, &used, "**Zustand:** %s — %d von %d Geräten nicht bereit\n\n",
            s->fail > 0 ? "blockiert" : (s->warn > 0 ? "eingeschränkt" : "bereit"),
            s->fail + s->warn, s->devices) != 0) {
        return -1;
    }
    if (s->firmware_old > 0 &&
        app(buf, cap, &used, "**%d Gerät(e) unter Mindest-Firmware.**\n\n", s->firmware_old) != 0) {
        return -1;
    }
    {
        static ro_timeline tl;
        char tb[4096];
        int tn;

        ro_timeline_build(recs, n, &tl);
        tn = ro_timeline_md(&tl, tb, sizeof(tb));
        if (tn < 0) {
            return -1;
        }
        if (tn > 0 && app(buf, cap, &used, "%s", tb) != 0) {
            return -1;
        }
    }

    if (app(buf, cap, &used, "## Standorte\n\n| Standort | ok | Warnung | Fehler | Zustand |\n|---|---|---|---|---|\n") != 0) {
        return -1;
    }
    for (i = 0; i < s->sites; i++) {
        const char *state = s->site_fail[i] ? "blockiert"
                          : (s->site_warn[i] ? "eingeschränkt" : "bereit");
        if (app(buf, cap, &used, "| %s | %d | %d | %d | %s |\n",
                s->site_name[i], s->site_ok[i], s->site_warn[i], s->site_fail[i], state) != 0) {
            return -1;
        }
    }

    if (app(buf, cap, &used, "\n## Handlungsbedarf nach Prüfung\n\n| Prüfung | Fehler | Warnung | übersprungen |\n|---|---|---|---|\n") != 0) {
        return -1;
    }
    for (k = 0; k < RO_CHECK_IDS; k++) {
        const ro_hotspot *h = &s->hotspots[k];
        if (h->fail == 0 && h->warn == 0) {
            continue;
        }
        if (app(buf, cap, &used, "| %s | %d | %d | %d |\n", h->id, h->fail, h->warn, h->skip) != 0) {
            return -1;
        }
    }

    if (app(buf, cap, &used, "\n## Nicht bereit\n\n") != 0) {
        return -1;
    }
    {
        int any = 0;
        for (i = 0; i < n; i++) {
            if (recs[i].verdict != CHK_OK) {
                char why[96];
                size_t w = 0;
                int k2;
                why[0] = '\0';
                for (k2 = 0; k2 < RO_CHECK_IDS; k2++) {
                    if (recs[i].status[k2] == CHK_FAIL || recs[i].status[k2] == CHK_WARN) {
                        int m = snprintf(why + w, sizeof(why) - w, "%s%s",
                                         w ? ", " : "", ro_check_ids[k2]);
                        if (m > 0) {
                            w += (size_t)m;
                        }
                    }
                }
                if (app(buf, cap, &used, "- %s / %s: %s (%s)\n",
                        recs[i].site, recs[i].device,
                        chk_verdict_name((chk_status)recs[i].verdict),
                        why[0] ? why : "ohne Detail") != 0) {
                    return -1;
                }
                any = 1;
            }
        }
        if (!any && app(buf, cap, &used, "keine — alle Geräte bereit.\n") != 0) {
            return -1;
        }
    }
    return (int)used;
}

int ro_render_csv(const ro_record *recs, int n, char *buf, size_t cap)
{
    size_t used = 0;
    int i;

    if (recs == NULL || buf == NULL) {
        return -1;
    }
    if (app(buf, cap, &used, "site;device;serial;timestamp;firmware;verdict;vbat_mv;resyncs;crc_errors\n") != 0) {
        return -1;
    }
    for (i = 0; i < n; i++) {
        if (app(buf, cap, &used, "%s;%s;%s;%s;%s;%s;%ld;%u;%u\n",
                recs[i].site, recs[i].device, recs[i].serial, recs[i].timestamp,
                recs[i].firmware, chk_status_name((chk_status)recs[i].verdict),
                recs[i].vbat_mv, recs[i].resyncs, recs[i].crc_errors) != 0) {
            return -1;
        }
    }
    return (int)used;
}

int ro_policy_exit(const ro_summary *s, int min_pass_permille, int any_fail_blocks)
{
    if (s == NULL) {
        return 2;
    }
    if (s->fail > 0) {
        /* Ein defektes Gerät bleibt sichtbar, auch wenn es den Rollout nicht blockiert. */
        return any_fail_blocks ? 2 : 1;
    }
    if (s->pass_permille < min_pass_permille) {
        return 2;
    }
    if (s->warn > 0 || s->firmware_old > 0) {
        return 1;
    }
    return 0;
}
