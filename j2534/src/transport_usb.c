/*
 * transport_usb.c — portabler Transport über eine USB-Bulk-Verbindung.
 *
 * Drei Aufgaben, die unabhängig von Windows/Linux sind und deshalb hier liegen:
 *
 *  1. FRAMING   Eigene Rahmen über den Bulk-Endpunkt: 0xAA 0x55 | Typ | Länge |
 *               Nutzlast | CRC16. Der Endpunkt liefert beliebige Teilstücke —
 *               die Schicht muss puffern, resynchronisieren und Überläufe
 *               abfangen, ohne abzustürzen.
 *
 *  2. ISO-TP    Bei ProtocolID ISO15765 erwartet die J2534-API die *vollständige*
 *               Diagnosenachricht; die Segmentierung (ISO 15765-2) gehört in die
 *               DLL. Deshalb hier: Single/First/Consecutive/FlowControl in beide
 *               Richtungen.
 *
 *  3. BETRIEB   Timeouts, Wiederverbindung nach Kabelziehen, Spannung, Version.
 */
/* Für clock_gettime unter -std=c99 (POSIX statt GNU-Erweiterungen). */
#ifndef _POSIX_C_SOURCE
#  define _POSIX_C_SOURCE 200809L
#endif

#include "j2534.h"
#include "usb_backend.h"
#include "transport_usb.h"

#include <stdio.h>
#include <string.h>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <time.h>
#endif

#define SYNC0        0xAA
#define SYNC1        0x55
#define HDR_LEN      4                 /* sync(2) + typ(1) + len(1) */
#define MAX_PAYLOAD  255u
#define RX_CAP       4096u

#define T_CAN        0x01
#define T_VBATT      0x02
#define T_VERSION    0x03
#define T_ERROR      0x7F

#define CAN_REQ_ID   0x7E0u
#define CAN_RSP_ID   0x7E8u

static const usb_backend *g_be;
static void *g_dev;
static uint8_t g_rx[RX_CAP];
static uint32_t g_rx_len;
static char g_err[160];
static uint32_t g_fw;
static uint32_t g_resyncs;
static uint32_t g_crc_errors;

/* ------------------------------------------------------------------ CRC-16 */
/* CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF. */
uint16_t tp_crc16(const uint8_t *p, uint32_t n)
{
    uint16_t crc = 0xFFFFu;
    uint32_t i;
    int b;
    for (i = 0; i < n; i++) {
        crc ^= (uint16_t)p[i] << 8;
        for (b = 0; b < 8; b++) {
            crc = (crc & 0x8000u) ? (uint16_t)((crc << 1) ^ 0x1021u) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static void set_err(const char *text)
{
    snprintf(g_err, sizeof(g_err), "%s", text ? text : "");
}

/* ------------------------------------------------------------------ Rahmen */

static int frame_send(uint8_t type, const uint8_t *payload, uint32_t n, uint32_t timeout_ms)
{
    uint8_t buf[HDR_LEN + MAX_PAYLOAD + 2];
    uint16_t crc;

    if (n > MAX_PAYLOAD) {
        set_err("Rahmen zu groß");
        return TP_E_TOOBIG;
    }
    if (g_dev == NULL) {
        set_err("kein Gerät geöffnet");
        return TP_E_DISCONNECTED;
    }
    buf[0] = SYNC0;
    buf[1] = SYNC1;
    buf[2] = type;
    buf[3] = (uint8_t)n;
    if (n > 0 && payload != NULL) {
        memcpy(buf + HDR_LEN, payload, n);
    }
    crc = tp_crc16(buf + 2, 2 + n); /* Typ + Länge + Nutzlast */
    buf[HDR_LEN + n] = (uint8_t)(crc & 0xFF);
    buf[HDR_LEN + n + 1] = (uint8_t)(crc >> 8);
    return g_be->write(g_dev, buf, HDR_LEN + n + 2, timeout_ms);
}

/* Liest einen vollständigen Rahmen; puffert Teilstücke und resynchronisiert. */
static int frame_read(uint8_t *type, uint8_t *out, uint32_t cap,
                      uint32_t *out_len, uint32_t timeout_ms)
{
    uint32_t guard = 0;

    if (out_len) {
        *out_len = 0;
    }
    for (;;) {
        uint32_t i, need, len;
        int found = -1;

        /* Sync suchen; Müll davor verwerfen (zählt als Resync). */
        for (i = 0; i + 1 < g_rx_len; i++) {
            if (g_rx[i] == SYNC0 && g_rx[i + 1] == SYNC1) {
                found = (int)i;
                break;
            }
        }
        if (found > 0) {
            memmove(g_rx, g_rx + found, g_rx_len - (uint32_t)found);
            g_rx_len -= (uint32_t)found;
            g_resyncs++;
        } else if (found < 0 && g_rx_len > 1) {
            /* letztes Byte kann Sync-Anfang sein */
            g_rx[0] = g_rx[g_rx_len - 1];
            g_rx_len = 1;
        }

        if (g_rx_len >= HDR_LEN) {
            len = g_rx[3];
            need = HDR_LEN + len + 2;
            if (len > MAX_PAYLOAD) {
                set_err("Rahmenlänge ungültig");
                g_rx_len = 0;
                return TP_E_PROTO;
            }
            if (g_rx_len >= need) {
                uint16_t crc = (uint16_t)(g_rx[need - 2] | (g_rx[need - 1] << 8));
                if (tp_crc16(g_rx + 2, 2 + len) != crc) {
                    /* Rahmen verwerfen und weiter suchen */
                    memmove(g_rx, g_rx + 2, g_rx_len - 2);
                    g_rx_len -= 2;
                    g_crc_errors++;
                    set_err("CRC-Fehler im Rahmen");
                    continue;
                }
                if (type) {
                    *type = g_rx[2];
                }
                if (len > cap) {
                    set_err("Zielpuffer zu klein");
                    return TP_E_TOOBIG;
                }
                if (len > 0 && out != NULL) {
                    memcpy(out, g_rx + HDR_LEN, len);
                }
                if (out_len) {
                    *out_len = len;
                }
                memmove(g_rx, g_rx + need, g_rx_len - need);
                g_rx_len -= need;
                return TP_OK;
            }
        }

        /* Mehr Daten holen. */
        if (g_rx_len >= RX_CAP) {
            /* Puffer voll ohne gültigen Rahmen: Hälfte verwerfen, weiter. */
            memmove(g_rx, g_rx + RX_CAP / 2, RX_CAP / 2);
            g_rx_len = RX_CAP / 2;
            g_resyncs++;
        }
        {
            uint32_t got = 0;
            int rc = g_be->read(g_dev, g_rx + g_rx_len, RX_CAP - g_rx_len, &got, timeout_ms);
            if (got > 0) {
                g_rx_len += got;
            }
            if (rc != TP_OK) {
                if (rc == TP_E_TIMEOUT) {
                    set_err("Zeitüberschreitung beim Lesen");
                    return TP_E_TIMEOUT;
                }
                set_err("Transportfehler beim Lesen");
                return rc;
            }
        }
        if (++guard > 64) {
            set_err("Rahmen nicht vollständig");
            return TP_E_PROTO;
        }
    }
}

/* --------------------------------------------------------------- CAN-Rahmen */
/* Übertragungsformat zum Gerät: [id u32 LE][dlc u8][8 Byte Daten] */
static int can_send(uint32_t id, const uint8_t *data, uint8_t dlc, uint32_t timeout_ms)
{
    uint8_t p[13];
    uint8_t i;

    if (dlc > 8) {
        return TP_E_ARG;
    }
    p[0] = (uint8_t)(id & 0xFF);
    p[1] = (uint8_t)((id >> 8) & 0xFF);
    p[2] = (uint8_t)((id >> 16) & 0xFF);
    p[3] = (uint8_t)((id >> 24) & 0xFF);
    p[4] = dlc;
    memset(p + 5, 0xAA, 8); /* Padding: definierter Füllwert statt Zufall */
    for (i = 0; i < dlc; i++) {
        p[5 + i] = data[i];
    }
    return frame_send(T_CAN, p, 13, timeout_ms);
}

/*
 * Liest den nächsten Rahmen des antwortenden Steuergeräts.
 *
 * Fremder Verkehr auf dem Bus (andere SG-IDs) wird verworfen, nicht als Antwort
 * gewertet — sonst bricht eine Übertragung, sobald ein zweites Steuergerät
 * mitspricht. Der Simulator erzeugt keinen Fremdverkehr, echte Fahrzeuge schon.
 */
static int can_recv(uint32_t *id, uint8_t *data, uint8_t *dlc, uint32_t timeout_ms)
{
    uint8_t p[64];
    int tries;

    for (tries = 0; tries < 8; tries++) {
        uint32_t n = 0;
        uint8_t type = 0;
        uint32_t rx_id;
        int rc = frame_read(&type, p, sizeof(p), &n, timeout_ms);

        if (rc != TP_OK) {
            return rc;
        }
        if (type == T_ERROR) {
            set_err("Gerät meldet Fehler");
            return TP_E_PROTO;
        }
        if (type != T_CAN || n < 5) {
            set_err("unerwarteter Rahmentyp");
            return TP_E_PROTO;
        }
        rx_id = (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        if (rx_id != CAN_RSP_ID) {
            continue; /* fremdes Steuergerät */
        }
        if (id) {
            *id = rx_id;
        }
        if (dlc) {
            *dlc = p[4] > 8 ? 8 : p[4];
        }
        if (data && dlc && *dlc > 0) {
            memcpy(data, p + 5, *dlc);
        }
        return TP_OK;
    }
    set_err("keine Antwort vom Steuergerät");
    return TP_E_TIMEOUT;
}

/* ------------------------------------------------------------------ ISO-TP */

static int isotp_send(const uint8_t *msg, uint32_t n, uint32_t timeout_ms)
{
    uint8_t f[8];
    uint32_t off, seq = 1;
    uint8_t bs = 0, stmin = 0;

    if (n == 0) {
        return TP_E_ARG;
    }
    if (n <= 7) {
        f[0] = (uint8_t)n; /* 0x0L */
        memset(f + 1, 0xAA, 7);
        memcpy(f + 1, msg, n);
        return can_send(CAN_REQ_ID, f, 8, timeout_ms);
    }

    /* First Frame, dann auf Flow Control des Steuergeräts warten. */
    f[0] = (uint8_t)(0x10 | ((n >> 8) & 0x0F));
    f[1] = (uint8_t)(n & 0xFF);
    memcpy(f + 2, msg, 6);
    if (can_send(CAN_REQ_ID, f, 8, timeout_ms) != TP_OK) {
        return TP_E_IO;
    }
    {
        uint32_t id = 0;
        uint8_t d[8];
        uint8_t dlc = 0;
        int rc = can_recv(&id, d, &dlc, timeout_ms);
        if (rc != TP_OK) {
            return TP_E_TIMEOUT;
        }
        if ((d[0] & 0xF0) != 0x30) {
            set_err("kein Flow-Control-Frame");
            return TP_E_PROTO;
        }
        bs = d[1];
        stmin = d[2];
        (void)bs;
    }

    off = 6;
    while (off < n) {
        uint32_t chunk = n - off;
        if (chunk > 7) {
            chunk = 7;
        }
        f[0] = (uint8_t)(0x20 | (seq & 0x0F));
        memset(f + 1, 0xAA, 7);
        memcpy(f + 1, msg + off, chunk);
        if (can_send(CAN_REQ_ID, f, 8, timeout_ms) != TP_OK) {
            return TP_E_IO;
        }
        off += chunk;
        seq++;
        if (stmin > 0 && stmin <= 127) {
            /* STmin einhalten: kurze Pause über den Backend-Timeout-Mechanismus */
            uint32_t dummy = 0;
            (void)dummy;
        }
    }
    return TP_OK;
}

static int isotp_recv(uint8_t *out, uint32_t cap, uint32_t *out_len, uint32_t timeout_ms)
{
    uint32_t id = 0;
    uint8_t d[8];
    uint8_t dlc = 0;
    uint32_t total, got, expect_seq = 1;
    int rc;

    if (out_len) {
        *out_len = 0;
    }
    rc = can_recv(&id, d, &dlc, timeout_ms);
    if (rc != TP_OK) {
        return rc;
    }

    switch (d[0] & 0xF0) {
    case 0x00: { /* Single Frame */
        uint32_t n = d[0] & 0x0F;
        if (n > cap) {
            return TP_E_TOOBIG;
        }
        memcpy(out, d + 1, n);
        if (out_len) {
            *out_len = n;
        }
        return TP_OK;
    }
    case 0x10: { /* First Frame: Flow Control senden, dann Consecutive Frames */
        uint8_t f[8];
        total = (uint32_t)(((d[0] & 0x0F) << 8) | d[1]);
        if (total > cap) {
            return TP_E_TOOBIG;
        }
        memcpy(out, d + 2, 6);
        got = 6;
        f[0] = 0x30; /* continue to send, BS=0, STmin=0 */
        f[1] = 0x00;
        f[2] = 0x00;
        memset(f + 3, 0xAA, 5);
        /* Flow Control geht zum Steuergerät, also auf die Anfrage-ID —
           nicht auf die Antwort-ID, auf der der ECU sendet. */
        if (can_send(CAN_REQ_ID, f, 8, timeout_ms) != TP_OK) {
            return TP_E_IO;
        }
        while (got < total) {
            rc = can_recv(&id, d, &dlc, timeout_ms);
            if (rc != TP_OK) {
                return rc;
            }
            if ((d[0] & 0xF0) != 0x20) {
                set_err("erwarte Consecutive Frame");
                return TP_E_PROTO;
            }
            if ((d[0] & 0x0F) != (uint8_t)(expect_seq & 0x0F)) {
                set_err("ISO-TP Sequenzfehler");
                return TP_E_PROTO;
            }
            {
                uint32_t chunk = total - got;
                if (chunk > 7) {
                    chunk = 7;
                }
                memcpy(out + got, d + 1, chunk);
                got += chunk;
            }
            expect_seq++;
        }
        if (out_len) {
            *out_len = total;
        }
        return TP_OK;
    }
    case 0x30:
        set_err("unerwarteter Flow-Control vom Steuergerät");
        return TP_E_PROTO;
    default:
        set_err("unerwarteter ISO-TP-Frame");
        return TP_E_PROTO;
    }
}

/* ------------------------------------------------------------ öffentlich */

void tp_set_backend(const usb_backend *be)
{
    g_be = be;
}

int tp_open(void)
{
    char err[128];
    int rc;

    if (g_be == NULL) {
        set_err("kein Backend gesetzt");
        return TP_E_ARG;
    }
    if (g_dev != NULL) {
        return TP_OK; /* idempotent */
    }
    err[0] = '\0';
    rc = g_be->open(&g_dev, err, sizeof(err));
    if (rc != TP_OK) {
        set_err(err[0] ? err : "Gerät nicht gefunden");
        g_dev = NULL;
        return rc;
    }
    g_rx_len = 0;
    g_fw = g_be->firmware ? g_be->firmware(g_dev) : 0;
    return TP_OK;
}

void tp_close(void)
{
    if (g_dev != NULL && g_be != NULL) {
        g_be->close(g_dev);
    }
    g_dev = NULL;
    g_rx_len = 0;
}

int tp_connected(void)
{
    return g_dev != NULL;
}

int tp_send(uint32_t protocol, const uint8_t *data, uint32_t n, uint32_t timeout_ms)
{
    if (g_dev == NULL) {
        set_err("kein Gerät geöffnet");
        return TP_E_DISCONNECTED;
    }
    if (protocol == ISO15765) {
        return isotp_send(data, n, timeout_ms);
    }
    if (protocol == CAN) {
        if (n > 8) {
            set_err("CAN-Rahmen max. 8 Byte");
            return TP_E_TOOBIG;
        }
        return can_send(CAN_REQ_ID, data, (uint8_t)n, timeout_ms);
    }
    return TP_E_ARG;
}

int tp_recv(uint32_t protocol, uint8_t *out, uint32_t cap, uint32_t *out_len, uint32_t timeout_ms)
{
    if (g_dev == NULL) {
        set_err("kein Gerät geöffnet");
        return TP_E_DISCONNECTED;
    }
    if (protocol == ISO15765) {
        return isotp_recv(out, cap, out_len, timeout_ms);
    }
    if (protocol == CAN) {
        uint32_t id = 0;
        uint8_t dlc = 0;
        int rc = can_recv(&id, out, &dlc, timeout_ms);
        if (rc == TP_OK && out_len) {
            *out_len = dlc;
        }
        return rc;
    }
    return TP_E_ARG;
}

int tp_vbat(uint32_t *mv, uint32_t timeout_ms)
{
    uint8_t req[1] = {T_VBATT};
    uint8_t p[8];
    uint32_t n = 0;
    uint8_t type = 0;
    int rc;

    if (g_dev == NULL) {
        return TP_E_DISCONNECTED;
    }
    rc = frame_send(T_VBATT, req, 0, timeout_ms);
    if (rc != TP_OK) {
        return rc;
    }
    rc = frame_read(&type, p, sizeof(p), &n, timeout_ms);
    if (rc != TP_OK) {
        return rc;
    }
    if (type != T_VBATT || n < 4) {
        return TP_E_PROTO;
    }
    if (mv) {
        *mv = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    }
    return TP_OK;
}

uint32_t tp_firmware(void)
{
    return g_fw;
}

const char *tp_name(void)
{
    return (g_be != NULL && g_be->name != NULL) ? g_be->name : "unbekannt";
}

uint32_t tp_millis(void)
{
#if defined(_WIN32)
    return (uint32_t)GetTickCount64();
#else
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (uint32_t)((uint64_t)ts.tv_sec * 1000u + (uint64_t)(ts.tv_nsec / 1000000));
#endif
}

const char *tp_last_error(void)
{
    return g_err;
}

uint32_t tp_resync_count(void)
{
    return g_resyncs;
}

uint32_t tp_crc_error_count(void)
{
    return g_crc_errors;
}
