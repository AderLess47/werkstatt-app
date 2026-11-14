/*
 * rollout.h — Rollout-Auswertung für Werkstattketten.
 *
 * Zwei getrennte Aufgaben:
 *   1. Prüfen passiert am Standort, weil das VCI dort steckt.
 *   2. Auswerten passiert zentral über die gesammelten Ergebnisse.
 *
 * Diese Datei ist nur für (2) zuständig: Einzelberichte einlesen, je Gerät
 * deduplizieren, zu einer Flottenaussage verdichten.
 */
#ifndef ROLLOUT_H
#define ROLLOUT_H

#include <stddef.h>
#include "checker.h"

#define RO_MAX_RECORDS   512
#define RO_MAX_SITES     32
#define RO_CHECK_IDS     6

/* Reihenfolge muss zu ro_check_ids passen. */
extern const char *ro_check_ids[RO_CHECK_IDS];

typedef struct {
    char site[32];
    char device[32];
    char serial[48];
    char timestamp[24];   /* ISO-8601, sortierbar */
    char firmware[16];
    int verdict;          /* chk_status */
    long vbat_mv;
    unsigned resyncs;
    unsigned crc_errors;
    int status[RO_CHECK_IDS]; /* chk_status je Prüfung; -1 = nicht im Bericht */
} ro_record;

typedef struct {
    const char *id;
    int fail;
    int warn;
    int skip;
    int ok;
} ro_hotspot;

typedef struct {
    int devices;              /* eindeutige Geräte nach Dedupe */
    int ok;
    int warn;
    int fail;
    int pass_permille;        /* Anteil "ok" in Promille (0..1000) */
    int firmware_old;         /* Geräte unter Mindest-Firmware */
    int sites;
    char site_name[RO_MAX_SITES][32];
    int site_ok[RO_MAX_SITES];
    int site_warn[RO_MAX_SITES];
    int site_fail[RO_MAX_SITES];
    ro_hotspot hotspots[RO_CHECK_IDS]; /* absteigend nach Handlungsbedarf */
} ro_summary;

/* Zerlegt eine Zeile im JSONL-Format (oder eine vci-check-JSON-Ausgabe). */
int ro_parse_line(const char *line, ro_record *r);

/* Serialisiert einen Datensatz als JSONL-Zeile (ohne Zeilenumbruch). */
int ro_to_json(const ro_record *r, char *buf, size_t cap);

/* Behält je (site,device) den neuesten Datensatz. Rückgabe: Anzahl in out. */
int ro_dedupe(const ro_record *in, int n, ro_record *out, int max);

void ro_summarize(const ro_record *recs, int n, ro_summary *s);

/* Markdown-Bericht. Rückgabe: Länge oder -1 bei Platzmangel. */
int ro_render_md(const ro_summary *s, const ro_record *recs, int n, char *buf, size_t cap);

/* CSV aller Datensätze für die Tabellenkalkulation. */
int ro_render_csv(const ro_record *recs, int n, char *buf, size_t cap);

/* Exit-Code nach Rollout-Politik: 0 = durch, 1 = Warnungen, 2 = blockiert. */
int ro_policy_exit(const ro_summary *s, int min_pass_permille, int any_fail_blocks);

/* ------------------------------------------------------------ Zeitverlauf */

#define RO_MAX_WEEKS 26

typedef struct {
    int year;              /* ISO-Jahr, kann vom Kalenderjahr abweichen */
    int week;              /* ISO-Woche 1..53 */
    int devices;           /* bis dahin erfasste Geräte */
    int ok;
    int warn;
    int fail;
    int pass_permille;
    int delta_permille;    /* gegenüber der Vorwoche */
    int has_prev;
    char top_id[16];       /* häufigste Auffälligkeit dieser Woche, "" = keine */
    int top_count;
} ro_week;

typedef struct {
    ro_week items[RO_MAX_WEEKS];
    int count;
    int skipped;           /* Datensätze ohne lesbaren Zeitstempel */
} ro_timeline;

/* ISO-Woche zu einem Datum. Rückgabe: Woche 1..53 oder 0 bei ungültigem Datum. */
int ro_iso_week(int y, int m, int d, int *iso_year);

/* Baut den Wochenverlauf: Zustand der Flotte am Ende jeder erfassten Woche. */
void ro_timeline_build(const ro_record *recs, int n, ro_timeline *t);

/* Markdown-Abschnitt. Rückgabe: Länge, 0 wenn keine Entwicklung sichtbar, -1 bei Platzmangel. */
int ro_timeline_md(const ro_timeline *t, char *buf, size_t cap);

/*
 * SVG-Diagramm der Bestandsquote für die Betreiber-Präsentation.
 * threshold_permille < 0 zeichnet keine Freigabeschwelle.
 * Rückgabe: Länge des SVG, 0 wenn keine Wochen vorhanden, -1 bei Platzmangel.
 */
int ro_chart_svg(const ro_timeline *t, const char *title, const char *subtitle,
                 int threshold_permille, char *buf, size_t cap);

#endif /* ROLLOUT_H */
