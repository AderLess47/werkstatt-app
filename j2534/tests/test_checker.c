/*
 * test_checker.c — Tests des VCI-Selbsttests.
 *
 * Geprüft wird nicht, dass irgendetwas läuft, sondern dass die Bewertung
 * stimmt: zu niedrige Spannung ist ein Fehler, alte Firmware ist ein Fehler,
 * ein fehlendes Steuergerät darf nicht als "VCI defekt" gemeldet werden.
 */
#include "checker.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <string.h>

extern const usb_backend sim_backend;
extern void sim_reset(void);
extern void sim_set_vbat(int mv);
extern void sim_set_firmware(unsigned version);
extern void sim_set_ecu_absent(int on);
extern void sim_set_disconnected(int on);
extern void sim_inject_garbage(int bytes);

static int g_failures = 0;

static void check(const char *name, int ok)
{
    printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    if (!ok) {
        g_failures++;
    }
}

static const chk_result *find(const chk_report *r, const char *id)
{
    int i;
    for (i = 0; i < r->count; i++) {
        if (strcmp(r->items[i].id, id) == 0) {
            return &r->items[i];
        }
    }
    return NULL;
}

static void reset(void)
{
    tp_close();
    sim_reset();
    tp_set_backend(&sim_backend);
}

int main(void)
{
    chk_report r;
    const chk_result *it;

    /* --- 1. Alles in Ordnung --------------------------------------------- */
    reset();
    check("gesundes VCI -> Rückgabe 0", chk_run(&r) == 0);
    check("Verdict OK", r.verdict == CHK_OK);
    check("6 Prüfungen", r.count == 6);
    check("Gerätename übernommen", strcmp(r.device, "sim-vci") == 0);
    check("Firmware erkannt", strcmp(r.firmware, "1.4") == 0);
    it = find(&r, "vbat");
    check("Spannung 12480 mV erkannt", it != NULL && it->value == 12480 && it->status == CHK_OK);
    it = find(&r, "bus");
    check("Bustest bestanden", it != NULL && it->status == CHK_OK);
    it = find(&r, "isotp");
    check("ISO-TP Mehrfachnachricht bestanden", it != NULL && it->status == CHK_OK && it->value == 20);

    /* --- 2. Spannung zu niedrig ------------------------------------------ */
    reset();
    sim_set_vbat(11500);
    check("11,5 V -> Fehler", chk_run(&r) == 2);
    it = find(&r, "vbat");
    check("vbat ist FAIL", it != NULL && it->status == CHK_FAIL);
    check("Handlungshinweis vorhanden", it != NULL && strstr(it->hint, "Ladegerät") != NULL);
    check("Bustest läuft trotzdem", find(&r, "bus")->status == CHK_OK);

    /* --- 3. Spannung schwach --------------------------------------------- */
    reset();
    sim_set_vbat(12200);
    check("12,2 V -> Warnung", chk_run(&r) == 1);
    check("vbat ist WARN", find(&r, "vbat")->status == CHK_WARN);
    check("Handlungshinweis vorhanden", strstr(find(&r, "vbat")->hint, "Batterie") != NULL);

    /* --- 4. Spannung hoch (Ladegerät) ------------------------------------ */
    reset();
    sim_set_vbat(15200);
    check("15,2 V -> Warnung", chk_run(&r) == 1);
    check("vbat ist WARN bei Überspannung", find(&r, "vbat")->status == CHK_WARN);

    /* --- 5. Alte Firmware ------------------------------------------------- */
    reset();
    sim_set_firmware(0x00010003u); /* 1.3 */
    check("Firmware 1.3 -> Fehler", chk_run(&r) == 2);
    it = find(&r, "firmware");
    check("firmware ist FAIL", it != NULL && it->status == CHK_FAIL);
    check("Meldung nennt Mindestversion", it != NULL && strstr(it->message, "1.4") != NULL);

    /* --- 6. Kein Steuergerät am Bus --------------------------------------- */
    reset();
    sim_set_ecu_absent(1);
    check("kein ECU -> Fehler", chk_run(&r) == 2);
    it = find(&r, "bus");
    check("bus ist FAIL", it != NULL && it->status == CHK_FAIL);
    check("Hinweis nennt Zündung", it != NULL && strstr(it->hint, "Zündung") != NULL);
    it = find(&r, "isotp");
    check("ISO-TP übersprungen, nicht als VCI-Fehler gemeldet",
          it != NULL && it->status == CHK_SKIP);

    /* --- 7. Störungen auf der Leitung ------------------------------------- */
    reset();
    sim_inject_garbage(3);
    chk_run(&r);
    it = find(&r, "line");
    check("Resync wird gezählt und gemeldet",
          it != NULL && it->value >= 1 && it->status == CHK_WARN);
    check("Zähler im Bericht", r.resyncs >= 1);

    /* --- 8. Gerät gar nicht vorhanden ------------------------------------- */
    reset();
    sim_set_disconnected(1);
    check("kein Gerät -> Fehler", chk_run(&r) == 2);
    check("nur die Geräteprüfung wird gelistet", r.count == 1);
    check("Verdict FAIL", r.verdict == CHK_FAIL);

    /* --- 9. JSON-Vertrag --------------------------------------------------- */
    reset();
    {
        char buf[4096];
        chk_run(&r);
        check("JSON erzeugt", chk_to_json(&r, buf, sizeof(buf)) > 0);
        check("JSON enthält Verdict", strstr(buf, "\"verdict\":\"ok\"") != NULL);
        check("JSON enthält Prüf-IDs",
              strstr(buf, "\"id\":\"device\"") && strstr(buf, "\"id\":\"vbat\"") &&
              strstr(buf, "\"id\":\"line\""));
        check("JSON beginnt und endet korrekt",
              buf[0] == '{' && buf[strlen(buf) - 1] == '}');
        check("JSON-Puffer zu klein wird erkannt", chk_to_json(&r, buf, 20) < 0);
    }

    printf("\n%d Prüfung(en) fehlgeschlagen.\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
