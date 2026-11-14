/*
 * test_session.c — Abnahmetest der J2534-ABI-Schicht gegen den Loopback-ECU.
 *
 * Geprüft wird der vollständige Lebenszyklus, den eine Diagnoseanwendung
 * durchläuft, plus die Negativfälle, die im Feld Support kosten:
 *   - doppeltes Öffnen, unbekanntes Gerät
 *   - nicht unterstütztes Protokoll (muss abgelehnt, nicht still beantwortet werden)
 *   - ungültige Botschaftslänge
 *   - IOCTL mit unbekanntem Parameter
 */
#include "j2534.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <string.h>

extern const usb_backend sim_backend;
extern void sim_reset(void);

static int g_failures = 0;

static void check(const char *name, int ok)
{
    printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    if (!ok) {
        g_failures++;
    }
}

static void make_msg(PASSTHRU_MSG *m, const unsigned char *data, uint32_t len)
{
    memset(m, 0, sizeof(*m));
    m->ProtocolID = ISO15765;
    m->DataSize = len;
    memcpy(m->Data, data, len);
}

int main(void)
{
    uint32_t dev = 0, ch = 0, n = 0, vbat = 0, cfg = 0, devcount = 0;
    char fw[80], dll[80], api[80], err[256];
    PASSTHRU_MSG msg, rx[4];
    SCONFIG cfgparam[1];
    SCONFIG_LIST_INTERNAL cfglist;

    /* Simuliertes Gerät als Hardware-Transport einsetzen. */
    sim_reset();
    tp_set_backend(&sim_backend);

    /* --- Gerät öffnen ---------------------------------------------------- */
    check("PassThruOpen", PassThruOpen("sim0", &dev) == STATUS_NOERROR && dev == 1);
    check("PassThruOpen doppelt -> ERR_DEVICE_IN_USE",
          PassThruOpen("sim0", &dev) == ERR_DEVICE_IN_USE);

    /* --- Verbindung ------------------------------------------------------ */
    check("PassThruConnect ISO15765 500k",
          PassThruConnect(1, ISO15765, CAN_29BIT_ID, 500000, &ch) == STATUS_NOERROR && ch == 1);
    check("PassThruConnect unbekanntes Protokoll -> abgelehnt",
          PassThruConnect(1, J1850VPW, 0, 500000, &ch) == ERR_INVALID_PROTOCOL_ID);
    check("PassThruConnect falsche Baudrate -> abgelehnt",
          PassThruConnect(1, ISO15765, 0, 115200, &ch) == ERR_INVALID_BAUDRATE);

    /* --- Version und Spannung ------------------------------------------- */
    check("PassThruReadVersion",
          PassThruReadVersion(1, fw, dll, api) == STATUS_NOERROR &&
          strcmp(api, "04.04") == 0);
    check("IOCTL READ_VBATT",
          PassThruIoctl(1, READ_VBATT, &vbat, NULL) == STATUS_NOERROR && vbat == 12480);

    /* --- Config --------------------------------------------------------- */
    cfgparam[0].Parameter = DATA_RATE;
    cfgparam[0].Value = 0;
    cfglist.NumOfParams = 1;
    cfglist.ConfigPtr = cfgparam;
    check("IOCTL GET_CONFIG DATA_RATE",
          PassThruIoctl(ch, GET_CONFIG, &cfglist, NULL) == STATUS_NOERROR &&
          cfgparam[0].Value == 500000);
    check("IOCTL unbekannter Parameter -> abgelehnt",
          PassThruIoctl(ch, GET_CONFIG, &(SCONFIG_LIST_INTERNAL){1, &(SCONFIG){0x99, 0}}, NULL)
              == ERR_INVALID_IOCTL_VALUE);

    /* --- Filter --------------------------------------------------------- */
    make_msg(&msg, (const unsigned char *)"\x00\x00\x07\xE0", 4);
    check("PassThruStartMsgFilter PASS_FILTER",
          PassThruStartMsgFilter(ch, PASS_FILTER, &msg, &msg, NULL, &cfg) == STATUS_NOERROR);
    check("PassThruStopMsgFilter",
          PassThruStopMsgFilter(ch, cfg) == STATUS_NOERROR);
    check("PassThruStopMsgFilter unbekannt -> abgelehnt",
          PassThruStopMsgFilter(ch, 999) == ERR_INVALID_FILTER_ID);

    /* --- TesterPresent -------------------------------------------------- */
    make_msg(&msg, (const unsigned char *)"\x3E\x00", 2);
    n = 1;
    check("PassThruWriteMsgs TesterPresent", PassThruWriteMsgs(ch, &msg, &n, 1000) == STATUS_NOERROR && n == 1);
    n = 4;
    memset(rx, 0, sizeof(rx));
    check("PassThruReadMsgs Antwort 7E 00",
          PassThruReadMsgs(ch, rx, &n, 1000) == STATUS_NOERROR &&
          n == 1 && rx[0].DataSize == 2 && rx[0].Data[0] == 0x7E && rx[0].Data[1] == 0x00);
    check("ReadMsgs auf leerem Puffer -> 0 Nachrichten, kein Fehler",
          (n = 4, PassThruReadMsgs(ch, rx, &n, 1000) == STATUS_NOERROR && n == 0));

    /* --- VIN lesen ------------------------------------------------------ */
    make_msg(&msg, (const unsigned char *)"\x22\xF1\x90", 3);
    n = 1;
    PassThruWriteMsgs(ch, &msg, &n, 1000);
    n = 4;
    memset(rx, 0, sizeof(rx));
    check("VIN via 22 F190",
          PassThruReadMsgs(ch, rx, &n, 1000) == STATUS_NOERROR && n == 1 &&
          rx[0].DataSize == 20 && rx[0].Data[0] == 0x62 &&
          memcmp(rx[0].Data + 3, "WVWZZZ1JZXW000001", 17) == 0);

    /* --- Negative Response (responsePending) ---------------------------- */
    make_msg(&msg, (const unsigned char *)"\x11\x01", 2);
    n = 1;
    PassThruWriteMsgs(ch, &msg, &n, 1000);
    n = 4;
    check("ECUReset -> 7F 11 78 (responsePending)",
          PassThruReadMsgs(ch, rx, &n, 1000) == STATUS_NOERROR && n == 1 &&
          rx[0].DataSize == 3 && rx[0].Data[0] == 0x7F && rx[0].Data[2] == 0x78);

    /* --- Fehlerfälle, die im Feld Support kosten ------------------------ */
    make_msg(&msg, (const unsigned char *)"\x3E\x00", 2);
    msg.DataSize = 0;
    n = 1;
    check("WriteMsgs mit Länge 0 -> ERR_INVALID_MSG",
          PassThruWriteMsgs(ch, &msg, &n, 1000) == ERR_INVALID_MSG);
    check("WriteMsgs auf ungültigem Kanal",
          PassThruWriteMsgs(77, &msg, &n, 1000) == ERR_INVALID_CHANNEL_ID);
    check("GetLastError liefert Text",
          PassThruGetLastError(err) == STATUS_NOERROR && strlen(err) > 0);

    /* --- Programmier-Spannung: bewusst abgelehnt ------------------------ */
    check("SetProgrammingVoltage -> ERR_NOT_SUPPORTED (keine Hardware)",
          PassThruSetProgrammingVoltage(1, 15, 18000) == ERR_NOT_SUPPORTED);

    /* --- Discovery (API 05.00) ------------------------------------------ */
    check("PassThruScanForDevices", PassThruScanForDevices(&devcount) == STATUS_NOERROR && devcount == 1);
    check("PassThruGetNextDevice", PassThruGetNextDevice(&dev) == STATUS_NOERROR && dev == 1);
    check("PassThruGetNextDevice erschöpft -> Fehler",
          PassThruGetNextDevice(&dev) != STATUS_NOERROR);

    /* --- Aufräumen ------------------------------------------------------ */
    check("PassThruDisconnect", PassThruDisconnect(ch) == STATUS_NOERROR);
    check("PassThruClose", PassThruClose(1) == STATUS_NOERROR);

    printf("\n%d Prüfung(en) fehlgeschlagen.\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
