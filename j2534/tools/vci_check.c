/*
 * vci_check.c — VCI-Selbsttest als Kommandozeilenwerkzeug.
 *
 * Aufruf:
 *   vci-check            Selbsttest am angeschlossenen VCI (Windows: WinUSB)
 *   vci-check --json     Bericht als JSON (für App und Automatisierung)
 *   vci-check --sim      gegen das simulierte Gerät (Entwicklung, CI)
 *
 * Rückgabewert: 0 = OK, 1 = Warnung, 2 = Fehler. Damit lässt sich der Test in
 * ein Onboarding-Skript oder eine Ketten-Rollout-Prüfung einhängen.
 */
#include "checker.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <string.h>

extern const usb_backend sim_backend;

#if defined(_WIN32)
extern const usb_backend winusb_backend;
#  define DEFAULT_BACKEND (&winusb_backend)
#else
#  define DEFAULT_BACKEND (&sim_backend)
#endif

#define LINE "──────────────────────────────────────────────────────────────"

/* Wortumbruch mit Einzug, damit Hinweise in der Werkstatt lesbar bleiben. */
static void print_wrapped(const char *prefix, int indent, const char *text, int width)
{
    int col;
    const char *p = text;

    printf("%s", prefix);
    col = indent;
    while (*p) {
        const char *word = p;
        int len = 0;
        while (word[len] && word[len] != ' ') {
            len++;
        }
        if (col + len > width && col > indent) {
            printf("\n%*s", indent, "");
            col = indent;
        }
        printf("%.*s", len, word);
        col += len;
        p = word + len;
        if (*p == ' ') {
            printf(" ");
            col++;
            p++;
        }
    }
    printf("\n");
}

static void print_text(const chk_report *r)
{
    int i;
    int passed = 0;

    printf("VCI-Selbsttest  ·  Gerät: %s  ·  Firmware %s\n", r->device, r->firmware);
    printf("%s\n", LINE);
    for (i = 0; i < r->count; i++) {
        const chk_result *it = &r->items[i];
        if (it->status == CHK_OK) {
            passed++;
        }
        printf("%s %-30s %s\n", chk_status_symbol(it->status), it->title, it->message);
        if (it->hint[0] != '\0') {
            print_wrapped("       → ", 9, it->hint, 78);
        }
    }
    printf("%s\n", LINE);
    printf("Ergebnis: %s — %d von %d Prüfungen bestanden\n",
           chk_verdict_name(r->verdict), passed, r->count);
    if (r->verdict == CHK_FAIL) {
        printf("Vor dem Auftrag beheben: mit einem fehlerhaften VCI entstehen Folgefehler am Fahrzeug.\n");
    } else if (r->verdict == CHK_WARN) {
        printf("Eingeschränkt nutzbar. Vor Schreibvorgängen (Codierung, Flashen) beheben.\n");
    }
}

int main(int argc, char **argv)
{
    chk_report report;
    int json = 0;
    int use_sim = 0;
    int i, rc;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--json") == 0) {
            json = 1;
        } else if (strcmp(argv[i], "--sim") == 0) {
            use_sim = 1;
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            printf("Aufruf: vci-check [--json] [--sim]\n");
            return 0;
        } else {
            fprintf(stderr, "unbekannte Option: %s\n", argv[i]);
            return 2;
        }
    }

    tp_set_backend(use_sim ? &sim_backend : DEFAULT_BACKEND);

    rc = chk_run(&report);
    if (json) {
        char buf[4096];
        if (chk_to_json(&report, buf, sizeof(buf)) < 0) {
            fprintf(stderr, "JSON-Puffer zu klein\n");
            return 2;
        }
        printf("%s\n", buf);
    } else {
        print_text(&report);
    }
    return rc;
}
