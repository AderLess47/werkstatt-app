/*
 * usb_sim.c — simuliertes USB-Gerät mit einem Steuergerät dahinter.
 *
 * Damit lässt sich der komplette Pfad testen, ohne Hardware:
 *   J2534-API -> ISO-TP -> Framing -> "USB" -> ECU -> Antwort -> zurück
 *
 * Zusätzlich lassen sich Feldfehler erzwingen, die sonst erst in der Werkstatt
 * auffallen: Müllbytes auf der Leitung, CRC-Fehler, Zeitüberschreitung,
 * Kabelziehen mitten im Betrieb.
 */
#include "usb_backend.h"
#include "transport_usb.h"

#include <stdio.h>
#include <string.h>

#define SIM_Q_MAX   16
#define SIM_FRAME   300

typedef struct {
    uint8_t buf[SIM_FRAME];
    uint32_t len;
} sim_frame;

static struct {
    int initialized;
    int open;
    sim_frame q[SIM_Q_MAX];
    int q_head, q_count;
    int garbage_bytes;       /* Müll vor dem nächsten Lesen einschieben */
    int crc_corrupt_once;    /* nächsten Rahmen mit falscher CRC liefern */
    int timeout_once;        /* nächsten Lesevorgang leer lassen */
    int disconnect;          /* Gerät "weg" */
    int vbat_mv;             /* gemeldete Bordspannung */
    uint32_t firmware;       /* gemeldete Firmware-Version */
    int ecu_absent;          /* kein Steuergerät am Bus */
    /* ISO-TP-Empfangspuffer für die Anfrage */
    uint8_t isotp[256];
    uint32_t isotp_len;
    uint32_t isotp_total;
} sim;

/* ------------------------------------------------------------------ Rahmen */

static void q_push(const uint8_t *frame, uint32_t len)
{
    int slot;
    if (sim.q_count >= SIM_Q_MAX) {
        return;
    }
    slot = (sim.q_head + sim.q_count) % SIM_Q_MAX;
    memcpy(sim.q[slot].buf, frame, len);
    sim.q[slot].len = len;
    sim.q_count++;
}

static void send_frame(uint8_t type, const uint8_t *payload, uint32_t n)
{
    uint8_t f[SIM_FRAME];
    uint16_t crc;
    if (n > 255) {
        return;
    }
    f[0] = 0xAA;
    f[1] = 0x55;
    f[2] = type;
    f[3] = (uint8_t)n;
    if (n) {
        memcpy(f + 4, payload, n);
    }
    crc = tp_crc16(f + 2, 2 + n);
    f[4 + n] = (uint8_t)(crc & 0xFF);
    f[5 + n] = (uint8_t)(crc >> 8);
    if (sim.crc_corrupt_once) {
        f[4 + n] ^= 0xFF;
        sim.crc_corrupt_once = 0;
    }
    q_push(f, 6 + n);
}

static void send_can(uint32_t id, const uint8_t *data, uint8_t dlc)
{
    uint8_t p[13];
    p[0] = (uint8_t)(id & 0xFF);
    p[1] = (uint8_t)((id >> 8) & 0xFF);
    p[2] = (uint8_t)((id >> 16) & 0xFF);
    p[3] = (uint8_t)((id >> 24) & 0xFF);
    p[4] = dlc;
    memset(p + 5, 0xAA, 8);
    memcpy(p + 5, data, dlc);
    send_frame(0x01, p, 13);
}

/* ------------------------------------------------------------ ISO-TP (ECU) */

static void isotp_respond(const uint8_t *msg, uint32_t n)
{
    uint8_t f[8];

    if (n <= 7) {
        f[0] = (uint8_t)n;
        memset(f + 1, 0xAA, 7);
        memcpy(f + 1, msg, n);
        send_can(0x7E8, f, 8);
        return;
    }
    f[0] = (uint8_t)(0x10 | ((n >> 8) & 0x0F));
    f[1] = (uint8_t)(n & 0xFF);
    memcpy(f + 2, msg, 6);
    send_can(0x7E8, f, 8);
    {
        uint32_t off = 6, seq = 1;
        while (off < n) {
            uint32_t chunk = n - off;
            if (chunk > 7) {
                chunk = 7;
            }
            f[0] = (uint8_t)(0x20 | (seq & 0x0F));
            memset(f + 1, 0xAA, 7);
            memcpy(f + 1, msg + off, chunk);
            send_can(0x7E8, f, 8);
            off += chunk;
            seq++;
        }
    }
}

/* UDS-Dienst des simulierten Steuergeräts */
static void ecu_handle(const uint8_t *req, uint32_t n)
{
    uint8_t r[64];
    static const char vin[] = "WVWZZZ1JZXW000001";

    if (n == 0 || sim.ecu_absent) {
        return; /* kein Steuergerät: keine Antwort, kein Fehler */
    }
    switch (req[0]) {
    case 0x3E:
        if (n >= 2 && req[1] == 0x80) {
            return; /* Antwort unterdrückt */
        }
        r[0] = 0x7E;
        r[1] = 0x00;
        isotp_respond(r, 2);
        break;
    case 0x10:
        r[0] = 0x50;
        r[1] = n >= 2 ? req[1] : 0x01;
        r[2] = 0x00;
        r[3] = 0x32;
        r[4] = 0x01;
        r[5] = 0xF4;
        isotp_respond(r, 6);
        break;
    case 0x22:
        if (n >= 3 && req[1] == 0xF1 && req[2] == 0x90) {
            r[0] = 0x62;
            r[1] = 0xF1;
            r[2] = 0x90;
            memcpy(r + 3, vin, 17);
            isotp_respond(r, 20); /* 20 Byte -> Multi-Frame */
        } else {
            r[0] = 0x7F;
            r[1] = 0x22;
            r[2] = 0x31; /* requestOutOfRange */
            isotp_respond(r, 3);
        }
        break;
    case 0x2E: /* WriteDataByIdentifier: Echo, damit langer Schreibpfad geprüft wird */
        r[0] = 0x6E;
        memcpy(r + 1, req + 1, n - 1);
        isotp_respond(r, n);
        break;
    case 0x11:
        r[0] = 0x7F;
        r[1] = 0x11;
        r[2] = 0x78;
        isotp_respond(r, 3);
        break;
    default:
        r[0] = 0x7F;
        r[1] = req[0];
        r[2] = 0x11;
        isotp_respond(r, 3);
        break;
    }
}

/* Empfangene CAN-Frames des Testers: ISO-TP zusammensetzen und beantworten. */
static void ecu_rx(const uint8_t *data, uint8_t dlc)
{
    if (dlc < 1) {
        return;
    }
    switch (data[0] & 0xF0) {
    case 0x00: {
        uint32_t n = data[0] & 0x0F;
        ecu_handle(data + 1, n);
        break;
    }
    case 0x10: {
        sim.isotp_total = (uint32_t)(((data[0] & 0x0F) << 8) | data[1]);
        memcpy(sim.isotp, data + 2, 6);
        sim.isotp_len = 6;
        /* Flow Control an den Tester */
        {
            uint8_t f[8] = {0x30, 0x00, 0x00, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA};
            send_can(0x7E8, f, 8);
        }
        break;
    }
    case 0x20: {
        uint32_t chunk = sim.isotp_total - sim.isotp_len;
        if (chunk > 7) {
            chunk = 7;
        }
        if (sim.isotp_len + chunk <= sizeof(sim.isotp)) {
            memcpy(sim.isotp + sim.isotp_len, data + 1, chunk);
            sim.isotp_len += chunk;
        }
        if (sim.isotp_len >= sim.isotp_total) {
            ecu_handle(sim.isotp, sim.isotp_total);
            sim.isotp_len = 0;
            sim.isotp_total = 0;
        }
        break;
    }
    default:
        break;
    }
}

/* ---------------------------------------------------------------- Backend */

static void sim_defaults(void)
{
    sim.vbat_mv = 12480;          /* 12,48 V */
    sim.firmware = 0x00010004u;   /* 1.4 */
}

static int sim_open(void **dev, char *err, int cap)
{
    if (!sim.initialized) {
        /* Standalone-Start (z. B. vci-check ohne Testvorbereitung) */
        sim_defaults();
        sim.initialized = 1;
    }
    if (sim.disconnect) {
        if (err && cap > 0) {
            snprintf(err, (size_t)cap, "Gerät nicht gefunden");
        }
        return TP_E_NOTFOUND;
    }
    sim.open = 1;
    sim.q_count = 0;
    sim.q_head = 0;
    sim.isotp_len = 0;
    *dev = &sim;
    return TP_OK;
}

static void sim_close(void *dev)
{
    (void)dev;
    sim.open = 0;
    sim.q_count = 0;
}

/* Nimmt einen geschriebenen Rahmen an: prüfen, auswerten, Antwort einreihen. */
static int sim_write(void *dev, const uint8_t *buf, uint32_t len, uint32_t timeout_ms)
{
    (void)dev;
    (void)timeout_ms;
    if (!sim.open) {
        return TP_E_DISCONNECTED;
    }
    if (len < 6 || buf[0] != 0xAA || buf[1] != 0x55) {
        return TP_E_PROTO;
    }
    {
        uint32_t n = buf[3];
        uint16_t crc = (uint16_t)(buf[4 + n] | (buf[5 + n] << 8));
        if (tp_crc16(buf + 2, 2 + n) != crc || len != 6 + n) {
            return TP_E_PROTO;
        }
        if (buf[2] == 0x01 && n >= 5) { /* CAN-Frame */
            ecu_rx(buf + 4 + 5, buf[4 + 4]);
        } else if (buf[2] == 0x02) {    /* Spannungsabfrage */
            uint8_t v[4];
            uint32_t mv = (uint32_t)sim.vbat_mv;
            v[0] = (uint8_t)(mv & 0xFF);
            v[1] = (uint8_t)((mv >> 8) & 0xFF);
            v[2] = (uint8_t)((mv >> 16) & 0xFF);
            v[3] = (uint8_t)((mv >> 24) & 0xFF);
            send_frame(0x02, v, 4);
        }
    }
    return TP_OK;
}

static int sim_read(void *dev, uint8_t *buf, uint32_t cap, uint32_t *got, uint32_t timeout_ms)
{
    (void)dev;
    (void)timeout_ms;
    *got = 0;
    if (!sim.open || sim.disconnect) {
        return TP_E_DISCONNECTED;
    }
    if (sim.timeout_once) {
        sim.timeout_once = 0;
        return TP_E_TIMEOUT;
    }
    {
        uint32_t off = 0;
        /* Müllbytes vor den Rahmen legen, damit in *einem* Lesen sowohl
           Resynchronisation als auch gültiger Rahmen geprüft werden. */
        if (sim.garbage_bytes > 0 && sim.q_count > 0) {
            uint32_t g = (uint32_t)sim.garbage_bytes;
            uint32_t i;
            if (g > cap) {
                g = cap;
            }
            for (i = 0; i < g; i++) {
                buf[i] = (uint8_t)(0x10 + i);
            }
            off = g;
            sim.garbage_bytes = 0;
        }
        if (sim.q_count > 0 && cap - off >= sim.q[sim.q_head].len) {
            sim_frame *f = &sim.q[sim.q_head];
            memcpy(buf + off, f->buf, f->len);
            off += f->len;
            sim.q_head = (sim.q_head + 1) % SIM_Q_MAX;
            sim.q_count--;
        }
        *got = off;
        if (off == 0) {
            return TP_E_TIMEOUT;
        }
    }
    return TP_OK;
}

static int sim_vbat(void *dev, uint32_t *mv)
{
    (void)dev;
    if (mv) {
        *mv = (uint32_t)sim.vbat_mv;
    }
    return TP_OK;
}

static uint32_t sim_firmware(void *dev)
{
    (void)dev;
    return sim.firmware;
}

const usb_backend sim_backend = {
    sim_open, sim_close, sim_write, sim_read, sim_vbat, sim_firmware, "sim-vci"
};

/* ------------------------------------------------- Steuerung für die Tests */

void sim_reset(void)
{
    memset(&sim, 0, sizeof(sim));
    sim_defaults();
    sim.initialized = 1;
}

void sim_set_vbat(int mv)
{
    sim.vbat_mv = mv;
}

void sim_set_firmware(uint32_t version)
{
    sim.firmware = version;
}

void sim_set_ecu_absent(int on)
{
    sim.ecu_absent = on;
}

void sim_inject_garbage(int bytes)
{
    sim.garbage_bytes = bytes;
}

void sim_corrupt_next_frame(void)
{
    sim.crc_corrupt_once = 1;
}

void sim_force_timeout(void)
{
    sim.timeout_once = 1;
}

void sim_set_disconnected(int on)
{
    sim.disconnect = on;
}
