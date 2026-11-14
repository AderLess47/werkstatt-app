/*
 * checker.c — die Selbsttest-Prüfungen.
 *
 * Aufbau: jede Prüfung liefert Status + Messwert + Klartext. Der Checker
 * entscheidet nichts über das Fahrzeug, nur über die Messkette.
 *
 * Schwellwerte sind bewusst als #define an einer Stelle, damit sie sich mit
 * Felderfahrung anpassen lassen, ohne Logik zu ändern.
 */
#include "j2534.h"
#include "checker.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/* Mindest-Firmware der Schnittstelle (1.4). */
#define FW_MIN              0x00010004u

/* Bordspannung in mV */
#define VBAT_FAIL_BELOW     11800   /* darunter: keine zuverlässige Diagnose */
#define VBAT_WARN_BELOW     12300   /* Batterie schwach, Standverbraucher? */
#define VBAT_WARN_ABOVE     14800   /* Ladegerät dran oder Überspannung */

/* Antwortzeit des Steuergeräts */
#define LAT_OK_MS           50
#define LAT_WARN_MS         200

/* Leitungsqualität */
#define LINE_WARN_ABOVE     1
#define LINE_FAIL_ABOVE     5

#define T_SHORT             300u
#define T_LONG              600u

static chk_result *add(chk_report *r, const char *id, const char *title)
{
    chk_result *it;
    if (r->count >= CHK_MAX) {
        return NULL;
    }
    it = &r->items[r->count++];
    memset(it, 0, sizeof(*it));
    it->id = id;
    it->title = title;
    it->status = CHK_OK;
    it->unit = "";
    return it;
}

static void finish(chk_result *it, chk_status st, const char *msg,
                   const char *hint, long value, const char *unit)
{
    if (it == NULL) {
        return;
    }
    it->status = st;
    it->value = value;
    it->unit = unit ? unit : "";
    snprintf(it->message, sizeof(it->message), "%s", msg ? msg : "");
    snprintf(it->hint, sizeof(it->hint), "%s",
             ((st == CHK_WARN || st == CHK_FAIL) && hint != NULL) ? hint : "");
}

static void raise_verdict(chk_report *r, chk_status st)
{
    if (st == CHK_FAIL && r->verdict != CHK_FAIL) {
        r->verdict = CHK_FAIL;
    } else if (st == CHK_WARN && r->verdict == CHK_OK) {
        r->verdict = CHK_WARN;
    }
}

static void fmt_version(uint32_t fw, char *out, size_t cap)
{
    snprintf(out, cap, "%u.%u", (unsigned)(fw >> 16), (unsigned)(fw & 0xFFFF));
}

int chk_run(chk_report *r)
{
    int owns = 0;
    uint32_t fw;
    chk_status bus_status = CHK_OK;
    unsigned resyncs_0, crc_0;

    if (r == NULL) {
        return 2;
    }
    memset(r, 0, sizeof(*r));
    r->verdict = CHK_OK;
    snprintf(r->device, sizeof(r->device), "%s", "unbekannt");
    snprintf(r->firmware, sizeof(r->firmware), "%s", "-");

    /* Die Zähler der Transportschicht sind kumulativ. Für einen Selbsttest
       zählt nur, was während dieses Laufs passiert ist. */
    resyncs_0 = tp_resync_count();
    crc_0 = tp_crc_error_count();

    /* --- 1. Gerät erreichbar ------------------------------------------- */
    {
        chk_result *it = add(r, "device", "Gerät erreichbar");
        if (!tp_connected()) {
            if (tp_open() != TP_OK) {
                char msg[160];
                snprintf(msg, sizeof(msg), "VCI nicht gefunden: %s", tp_last_error());
                finish(it, CHK_FAIL, msg,
                       "Kabel und USB-Anschluss prüfen, Dongle abziehen und neu stecken, "
                       "Treiber/INF installieren.", 0, "");
                raise_verdict(r, CHK_FAIL);
                return 2;
            }
            owns = 1;
        }
        snprintf(r->device, sizeof(r->device), "%s", tp_name());
        finish(it, CHK_OK, "VCI antwortet", "", 0, "");
    }

    /* --- 2. Firmware ---------------------------------------------------- */
    {
        chk_result *it = add(r, "firmware", "Firmware");
        char msg[160];
        fw = tp_firmware();
        fmt_version(fw, r->firmware, sizeof(r->firmware));
        if (fw == 0) {
            snprintf(msg, sizeof(msg), "Firmware-Version nicht lesbar");
            finish(it, CHK_WARN, msg, "Firmware-Update über das Update-Werkzeug einspielen.", 0, "");
            raise_verdict(r, CHK_WARN);
        } else if (fw < FW_MIN) {
            char minv[16];
            fmt_version(FW_MIN, minv, sizeof(minv));
            snprintf(msg, sizeof(msg), "Firmware %s ist zu alt (mindestens %s)", r->firmware, minv);
            finish(it, CHK_FAIL, msg, "Firmware aktualisieren, sonst fehlen Funktionen und Timing-Parameter.",
                   (long)fw, "");
            raise_verdict(r, CHK_FAIL);
        } else {
            snprintf(msg, sizeof(msg), "Firmware %s", r->firmware);
            finish(it, CHK_OK, msg, "", (long)fw, "");
        }
    }

    /* --- 3. Bordspannung ------------------------------------------------ */
    {
        chk_result *it = add(r, "vbat", "Bordspannung");
        uint32_t mv = 0;
        char msg[160];

        if (tp_vbat(&mv, T_SHORT) != TP_OK) {
            finish(it, CHK_FAIL, "Spannung nicht messbar",
                   "VCI misst die Klemme 30 nicht. Zündung einschalten, OBD-Stecker prüfen.", 0, "mV");
            raise_verdict(r, CHK_FAIL);
        } else if (mv < VBAT_FAIL_BELOW) {
            snprintf(msg, sizeof(msg), "Bordspannung %.2f V ist zu niedrig", (double)mv / 1000.0);
            finish(it, CHK_FAIL, msg,
                   "Ladegerät anschließen. Unter 11,8 V brechen Schreibvorgänge ab und "
                   "Steuergeräte können in den Programmiermodus fallen.", (long)mv, "mV");
            raise_verdict(r, CHK_FAIL);
        } else if (mv < VBAT_WARN_BELOW) {
            snprintf(msg, sizeof(msg), "Bordspannung %.2f V ist schwach", (double)mv / 1000.0);
            finish(it, CHK_WARN, msg, "Batterie prüfen oder Ladegerät anschließen, bevor geschrieben wird.",
                   (long)mv, "mV");
            raise_verdict(r, CHK_WARN);
        } else if (mv > VBAT_WARN_ABOVE) {
            snprintf(msg, sizeof(msg), "Bordspannung %.2f V ist hoch", (double)mv / 1000.0);
            finish(it, CHK_WARN, msg, "Ladegerät im Stützbetrieb? Spannung vor dem Flashen auf 13,5–14,4 V begrenzen.",
                   (long)mv, "mV");
            raise_verdict(r, CHK_WARN);
        } else {
            snprintf(msg, sizeof(msg), "Bordspannung %.2f V", (double)mv / 1000.0);
            finish(it, CHK_OK, msg, "", (long)mv, "mV");
        }
    }

    /* --- 4. Fahrzeugbus -------------------------------------------------- */
    {
        chk_result *it = add(r, "bus", "Fahrzeugbus");
        const uint8_t req[2] = {0x3E, 0x00};
        uint8_t buf[64];
        uint32_t n = 0;
        uint32_t t0, dt;
        int ok;

        t0 = tp_millis();
        ok = (tp_send(ISO15765, req, 2, T_SHORT) == TP_OK);
        if (ok) {
            ok = (tp_recv(ISO15765, buf, sizeof(buf), &n, T_LONG) == TP_OK) && n >= 2 && buf[0] == 0x7E;
        }
        dt = tp_millis() - t0;

        if (!ok) {
            finish(it, CHK_FAIL, "Kein Steuergerät antwortet",
                   "Zündung ein, Klemme 15 prüfen. Adapter auf dem richtigen CAN-Pin? "
                   "Fahrzeug mit anderem Protokoll (K-Line/DoIP) braucht einen anderen Kanal.", 0, "ms");
            raise_verdict(r, CHK_FAIL);
        } else if (dt > LAT_WARN_MS) {
            char msg[160];
            snprintf(msg, sizeof(msg), "Antwort erst nach %lu ms", (unsigned long)dt);
            finish(it, CHK_WARN, msg, "Leitung oder Gateway ist langsam. Buslast und Steckverbinder prüfen.",
                   (long)dt, "ms");
            raise_verdict(r, CHK_WARN);
        } else {
            char msg[160];
            snprintf(msg, sizeof(msg), "Antwort in %lu ms%s", (unsigned long)dt,
                     dt <= LAT_OK_MS ? "" : " (träge)");
            finish(it, dt <= LAT_OK_MS ? CHK_OK : CHK_WARN, msg, "", (long)dt, "ms");
            if (dt > LAT_OK_MS) {
                raise_verdict(r, CHK_WARN);
            }
        }
        bus_status = it->status;
    }

    /* --- 5. ISO-TP Mehrfachnachricht ------------------------------------- */
    {
        chk_result *it = add(r, "isotp", "ISO-TP Mehrfachnachricht");
        const uint8_t req[3] = {0x22, 0xF1, 0x90};
        uint8_t buf[64];
        uint32_t n = 0;
        if (bus_status == CHK_FAIL) {
            finish(it, CHK_SKIP, "übersprungen (kein Bus)", "", 0, "Byte");
        } else if (tp_send(ISO15765, req, 3, T_SHORT) != TP_OK ||
                   tp_recv(ISO15765, buf, sizeof(buf), &n, T_LONG) != TP_OK) {
            finish(it, CHK_FAIL, "Mehrfachnachricht fehlgeschlagen",
                   "Segmentierung gestört: Leitung prüfen, anderes VCI testen.", 0, "Byte");
            raise_verdict(r, CHK_FAIL);
        } else if (n < 8) {
            char msg[160];
            snprintf(msg, sizeof(msg), "Antwort nur %lu Byte (keine Segmentierung)", (unsigned long)n);
            finish(it, CHK_WARN, msg, "Steuergerät liefert keine lange Antwort; Test mit bekannter VIN wiederholen.",
                   (long)n, "Byte");
            raise_verdict(r, CHK_WARN);
        } else {
            char msg[160];
            snprintf(msg, sizeof(msg), "Mehrfachnachricht gelesen (%lu Byte)", (unsigned long)n);
            finish(it, CHK_OK, msg, "", (long)n, "Byte");
        }
    }

    /* --- 6. Leitungsqualität --------------------------------------------- */
    {
        chk_result *it = add(r, "line", "Leitungsqualität");
        char msg[160];
        unsigned resyncs = tp_resync_count() - resyncs_0;
        unsigned crc = tp_crc_error_count() - crc_0;
        unsigned bad = resyncs + crc;

        r->resyncs = resyncs;
        r->crc_errors = crc;
        snprintf(msg, sizeof(msg), "%u Resync, %u CRC-Fehler", resyncs, crc);
        if (bad > LINE_FAIL_ABOVE) {
            finish(it, CHK_FAIL, msg,
                   "Verkabelung oder Störquelle: USB-Kabel tauschen, nicht am Zündverteiler/Umrichter betreiben.",
                   (long)bad, "");
            raise_verdict(r, CHK_FAIL);
        } else if (bad >= LINE_WARN_ABOVE) {
            finish(it, CHK_WARN, msg,
                   "Vereinzelte Störungen. Kabel und Steckverbinder prüfen, Test wiederholen.", (long)bad, "");
            raise_verdict(r, CHK_WARN);
        } else {
            finish(it, CHK_OK, msg, "", 0, "");
        }
    }

    if (owns) {
        tp_close();
    }
    return (int)r->verdict;
}

/* ------------------------------------------------------------------- JSON */

const char *chk_status_name(chk_status s)
{
    switch (s) {
    case CHK_OK:   return "ok";
    case CHK_WARN: return "warn";
    case CHK_FAIL: return "fail";
    default:       return "skip";
    }
}

const char *chk_verdict_name(chk_status s)
{
    switch (s) {
    case CHK_OK:   return "OK";
    case CHK_WARN: return "WARNUNG";
    case CHK_FAIL: return "FEHLER";
    default:       return "-";
    }
}

const char *chk_status_symbol(chk_status s)
{
    switch (s) {
    case CHK_OK:   return "[OK]";
    case CHK_WARN: return "[--]";
    case CHK_FAIL: return "[!!]";
    default:       return "[  ]";
    }
}

static int append(char *buf, size_t cap, size_t *used, const char *fmt, ...)
{
    va_list ap;
    int n;

    if (*used >= cap) {
        return -1;
    }
    va_start(ap, fmt);
    n = vsnprintf(buf + *used, cap - *used, fmt, ap);
    va_end(ap);
    if (n < 0) {
        return -1;
    }
    if ((size_t)n >= cap - *used) {
        return -1;
    }
    *used += (size_t)n;
    return (int)*used;
}

int chk_to_json(const chk_report *r, char *buf, size_t cap)
{
    size_t used = 0;
    int i;

    if (r == NULL || buf == NULL || cap == 0) {
        return -1;
    }
    if (append(buf, cap, &used,
               "{\"device\":\"%s\",\"firmware\":\"%s\",\"verdict\":\"%s\","
               "\"resyncs\":%u,\"crc_errors\":%u,\"checks\":[",
               r->device, r->firmware, chk_status_name(r->verdict),
               r->resyncs, r->crc_errors) < 0) {
        return -1;
    }
    for (i = 0; i < r->count; i++) {
        const chk_result *it = &r->items[i];
        if (append(buf, cap, &used,
                   "%s{\"id\":\"%s\",\"status\":\"%s\",\"value\":%ld,\"unit\":\"%s\","
                   "\"message\":\"%s\",\"hint\":\"%s\"}",
                   i ? "," : "", it->id, chk_status_name(it->status), it->value,
                   it->unit, it->message, it->hint) < 0) {
            return -1;
        }
    }
    if (append(buf, cap, &used, "]}") < 0) {
        return -1;
    }
    return (int)used;
}
