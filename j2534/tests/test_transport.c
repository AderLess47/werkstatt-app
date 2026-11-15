/*
 * test_transport.c — Abnahmetests der Transport- und ISO-TP-Schicht
 * gegen das simulierte USB-Gerät.
 *
 * Geprüft werden genau die Fälle, die im Feld Zeit kosten:
 * Teilstücke auf dem Bulk-Endpunkt, Müllbytes auf der Leitung, CRC-Fehler,
 * Zeitüberschreitung, Kabelziehen, zu lange Nachrichten.
 */
#include "j2534.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <string.h>

extern const usb_backend sim_backend;
extern void sim_reset(void);
extern void sim_inject_garbage(int bytes);
extern void sim_corrupt_next_frame(void);
extern void sim_force_timeout(void);
extern void sim_set_disconnected(int on);
extern void sim_set_flow_control(int bs, int stmin);
extern void sim_flow_wait_once(void);
extern void sim_force_bad_single_frame(void);

static int g_failures = 0;

static void check(const char *name, int ok)
{
    printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    if (!ok) {
        g_failures++;
    }
}

int main(void)
{
    uint8_t buf[64];
    uint32_t n = 0, mv = 0;

    sim_reset();
    tp_set_backend(&sim_backend);

    /* --- CRC ------------------------------------------------------------- */
    /* Referenzwert CRC-16/CCITT-FALSE über "123456789" = 0x29B1 */
    check("CRC-16/CCITT Referenzwert",
          tp_crc16((const uint8_t *)"123456789", 9) == 0x29B1);

    /* --- Öffnen ---------------------------------------------------------- */
    check("tp_open", tp_open() == TP_OK && tp_connected());
    check("Firmware-Version gelesen", tp_firmware() == 0x00010004u);

    /* --- Spannung -------------------------------------------------------- */
    check("Batteriespannung 12,48 V",
          tp_vbat(&mv, 100) == TP_OK && mv == 12480);

    /* --- Single Frame ---------------------------------------------------- */
    {
        const uint8_t req[2] = {0x3E, 0x00};
        check("ISO-TP Single Frame senden",
              tp_send(ISO15765, req, 2, 100) == TP_OK);
        check("ISO-TP Single Frame empfangen",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_OK &&
              n == 2 && buf[0] == 0x7E && buf[1] == 0x00);
    }

    /* --- Multi-Frame Empfang (VIN, 20 Byte) ------------------------------ */
    {
        const uint8_t req[3] = {0x22, 0xF1, 0x90};
        tp_send(ISO15765, req, 3, 100);
        check("VIN: 20 Byte über First/Consecutive Frames",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_OK &&
              n == 20 && buf[0] == 0x62 && memcmp(buf + 3, "WVWZZZ1JZXW000001", 17) == 0);
    }

    /* --- Multi-Frame Senden und Empfangen (20 Byte rein, 20 Byte raus) --- */
    {
        uint8_t req[20];
        req[0] = 0x2E;
        req[1] = 0xF1;
        req[2] = 0x90;
        memcpy(req + 3, "WVWZZZ1JZXW000001", 17);
        check("ISO-TP Multi-Frame senden (20 Byte)",
              tp_send(ISO15765, req, 20, 100) == TP_OK);
        check("ISO-TP Multi-Frame Antwort (Echo 6E ...)",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_OK &&
              n == 20 && buf[0] == 0x6E);
    }

    /* --- Müllbytes: Resynchronisation ------------------------------------ */
    {
        const uint8_t req[2] = {0x3E, 0x00};
        uint32_t before = tp_resync_count();
        sim_inject_garbage(5);
        tp_send(ISO15765, req, 2, 100);
        check("Resync nach 5 Müllbytes, Antwort korrekt",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_OK &&
              n == 2 && buf[0] == 0x7E && tp_resync_count() > before);
    }

    /* --- CRC-Fehler: verwerfen, nicht abstürzen -------------------------- */
    {
        const uint8_t req[2] = {0x3E, 0x00};
        sim_corrupt_next_frame();
        tp_send(ISO15765, req, 2, 100);
        check("CRC-Fehler führt zu Timeout statt Fehlantwort",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_E_TIMEOUT);
        check("CRC-Fehler gezählt", tp_crc_error_count() > 0);
        tp_send(ISO15765, req, 2, 100);
        check("nach CRC-Fehler wieder sauber",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_OK && n == 2);
    }

    /* --- Zeitüberschreitung ---------------------------------------------- */
    {
        const uint8_t req[2] = {0x3E, 0x00};
        sim_force_timeout();
        tp_send(ISO15765, req, 2, 100);
        check("erzwungene Zeitüberschreitung", tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_E_TIMEOUT);
        /* Antwort des vorherigen Versuchs ist noch in der Warteschlange */
        tp_recv(ISO15765, buf, sizeof(buf), &n, 100);
    }

    /* --- Grenzen ---------------------------------------------------------- */
    {
        uint8_t big[9] = {0};
        check("CAN-Rahmen > 8 Byte abgelehnt",
              tp_send(CAN, big, 9, 100) == TP_E_TOOBIG);
        check("leere Nachricht abgelehnt", tp_send(ISO15765, big, 0, 100) == TP_E_ARG);
        check("unbekanntes Protokoll abgelehnt", tp_send(0x99, big, 2, 100) == TP_E_ARG);
    }

    /* --- Lange Nutzlast: SFD-Token (324 Byte) ---------------------------- */
    {
        static uint8_t req[324];
        static uint8_t echo[512];
        uint32_t i;

        req[0] = 0x2E; /* WriteDataByIdentifier — das SG spiegelt die Nutzlast */
        for (i = 1; i < sizeof(req); i++) {
            req[i] = (uint8_t)(i & 0xFF);
        }

        sim_set_flow_control(2, 1); /* BS=2, STmin=1 ms: 23 Blöcke */
        check("324 Byte mit BS=2 und STmin gehen raus",
              tp_send(ISO15765, req, sizeof(req), 100) == TP_OK);
        /* Die positive Antwort auf 0x2E ist 0x6E mit derselben Nutzlast. */
        check("Echo der 324 Byte kommt vollständig zurück",
              tp_recv(ISO15765, echo, sizeof(echo), &n, 500) == TP_OK &&
              n == sizeof(req) && echo[0] == 0x6E &&
              memcmp(echo + 1, req + 1, sizeof(req) - 1) == 0);

        sim_set_flow_control(0, 0);
        sim_flow_wait_once();
        check("Vertröstung (FC 0x31) wird hingenommen",
              tp_send(ISO15765, req, sizeof(req), 100) == TP_OK);
        (void)tp_recv(ISO15765, echo, sizeof(echo), &n, 500);
    }

    /* --- Unzulässige Längenfelder ---------------------------------------- */
    {
        const uint8_t req[2] = {0x3E, 0x00};
        static uint8_t too_long[4096];

        sim_force_bad_single_frame();
        tp_send(ISO15765, req, 2, 100);
        check("Single Frame mit Längenfeld 15 wird abgelehnt",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_E_PROTO);
        tp_send(ISO15765, req, 2, 100);
        check("nach unzulässigem Rahmen wieder sauber",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_OK && n == 2);

        check("Nachricht über 4095 Byte abgelehnt",
              tp_send(ISO15765, too_long, sizeof(too_long), 100) == TP_E_TOOBIG);
    }

    /* --- Kabelziehen ------------------------------------------------------ */
    {
        sim_set_disconnected(1);
        check("Gerät weg -> TP_E_DISCONNECTED",
              tp_recv(ISO15765, buf, sizeof(buf), &n, 100) == TP_E_DISCONNECTED);
        sim_set_disconnected(0);
        tp_close();
        check("nach Wiederverbindung wieder nutzbar",
              tp_open() == TP_OK && tp_connected());
    }

    tp_close();
    check("Schließen setzt Zustand zurück", !tp_connected());

    printf("\n%d Prüfung(en) fehlgeschlagen.\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
