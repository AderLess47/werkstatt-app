/*
 * checker.h — VCI-Selbsttest für die Werkstatt.
 *
 * Der Checker beantwortet die Frage, die vor jedem Diagnoseauftrag steht:
 * "Ist der Dongle in Ordnung, oder suche ich gleich am falschen Ende?"
 *
 * Ergebnis ist eine Liste von Prüfungen mit Status, Messwert und einem Hinweis,
 * was zu tun ist. Dieselbe Struktur geht als JSON an die App-Oberfläche.
 */
#ifndef CHECKER_H
#define CHECKER_H

#include <stddef.h>

typedef enum {
    CHK_OK = 0,
    CHK_WARN = 1,
    CHK_FAIL = 2,
    CHK_SKIP = 3
} chk_status;

typedef struct {
    const char *id;          /* stabiler Schlüssel, z. B. "vbat" */
    const char *title;       /* Anzeigetitel */
    chk_status status;
    long value;              /* Messwert, 0 wenn ohne Messwert */
    const char *unit;        /* "mV", "ms", "Byte" oder "" */
    char message[160];       /* was gemessen wurde, in Klartext */
    char hint[200];          /* was die Werkstatt tun soll (nur bei WARN/FAIL) */
} chk_result;

#define CHK_MAX 10

typedef struct {
    char device[32];
    char firmware[16];
    chk_status verdict;
    int count;
    chk_result items[CHK_MAX];
    unsigned resyncs;
    unsigned crc_errors;
} chk_report;

/* Führt den Selbsttest aus. Rückgabe: 0 = OK, 1 = Warnung, 2 = Fehler. */
int chk_run(chk_report *r);

/* Serialisiert den Bericht als JSON. Rückgabe: Länge oder -1 bei Platzmangel. */
int chk_to_json(const chk_report *r, char *buf, size_t cap);

const char *chk_status_name(chk_status s);   /* "ok" / "warn" / "fail" / "skip" */
const char *chk_verdict_name(chk_status s);  /* "OK" / "WARNUNG" / "FEHLER" / "-" */
const char *chk_status_symbol(chk_status s); /* "[OK]" / "[--]" / "[!!]" */

#endif /* CHECKER_H */
