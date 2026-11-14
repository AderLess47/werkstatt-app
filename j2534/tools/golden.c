/*
 * golden.c — erzeugt den Konformitätskorpus für den Rust-Kern.
 *
 * Die C-Implementierung ist der unabhängige Zeuge für das Wire-Format: dieser
 * Generator schickt echte UDS-Anfragen durch Framing und ISO-TP gegen das
 * simulierte Gerät und schreibt mit, welche CAN-Rahmen tatsächlich entstehen.
 *
 * Der Rust-ISO-TP muss dieselben Rahmen erzeugen und dieselben Antworten
 * zusammensetzen — sonst weicht die App vom Verhalten der DLL ab.
 *
 * Ausgabe: core/tests/corpus/isotp.txt (siehe `make corpus`).
 */
#include "j2534.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <string.h>

extern const usb_backend sim_backend;
void sim_reset(void);

#define MAX_FRAMES 32

typedef struct {
    uint32_t id;
    uint8_t dlc;
    uint8_t data[8];
} cap_frame;

static cap_frame f_tx[MAX_FRAMES];    /* Tester -> ECU beim Senden */
static cap_frame f_in[MAX_FRAMES];    /* ECU -> Tester beim Senden (Flow Control) */
static cap_frame f_out[MAX_FRAMES];   /* Tester -> ECU beim Empfangen (Flow Control) */
static cap_frame f_rx[MAX_FRAMES];    /* ECU -> Tester beim Empfangen */
static int n_tx, n_in, n_out, n_rx;
static int phase;                     /* 1 = senden, 2 = empfangen */

/* Rahmenformat zum Gerät: 0xAA 0x55 | Typ | Länge | id(4) | dlc | Daten(8) | CRC */
static void push(cap_frame *arr, int *n, const uint8_t *buf, uint32_t len)
{
    if (*n >= MAX_FRAMES || len < 13) {
        return;
    }
    if (buf[0] != 0xAA || buf[1] != 0x55 || buf[2] != 0x01) {
        return;
    }
    arr[*n].id = (uint32_t)buf[4] | ((uint32_t)buf[5] << 8) |
                 ((uint32_t)buf[6] << 16) | ((uint32_t)buf[7] << 24);
    arr[*n].dlc = buf[8] > 8 ? 8 : buf[8];
    memcpy(arr[*n].data, buf + 9, 8);
    (*n)++;
}

static int cap_write(void *dev, const uint8_t *buf, uint32_t len, uint32_t timeout_ms)
{
    if (phase == 1) {
        push(f_tx, &n_tx, buf, len);
    } else if (phase == 2) {
        push(f_out, &n_out, buf, len);
    }
    return sim_backend.write(dev, buf, len, timeout_ms);
}

static int cap_read(void *dev, uint8_t *buf, uint32_t cap, uint32_t *got, uint32_t timeout_ms)
{
    int rc = sim_backend.read(dev, buf, cap, got, timeout_ms);

    if (rc == TP_OK && *got >= 13) {
        if (phase == 1) {
            push(f_in, &n_in, buf, *got);
        } else if (phase == 2) {
            push(f_rx, &n_rx, buf, *got);
        }
    }
    return rc;
}

static const usb_backend *cap_backend(void)
{
    static usb_backend be;
    static int init;

    if (!init) {
        be = sim_backend;   /* open/close/vbat/firmware/name übernehmen */
        be.write = cap_write;
        be.read = cap_read;
        init = 1;
    }
    return &be;
}

static void print_hex(const uint8_t *p, uint32_t n)
{
    uint32_t i;
    for (i = 0; i < n; i++) {
        printf("%02X", p[i]);
    }
}

static void put_frames(const char *role, const cap_frame *arr, int n)
{
    int i;
    for (i = 0; i < n; i++) {
        printf("%s %03X %u ", role, arr[i].id, (unsigned)arr[i].dlc);
        print_hex(arr[i].data, arr[i].dlc);
        printf("\n");
    }
}

static void scenario(const char *name, const char *desc, const uint8_t *req, uint32_t n)
{
    uint8_t out[64];
    uint32_t olen = 0;
    int rs, rr;

    sim_reset();
    tp_set_backend(cap_backend());
    tp_open();
    n_tx = n_in = n_out = n_rx = 0;

    phase = 1;
    rs = tp_send(ISO15765, req, n, 500);
    phase = 2;
    rr = tp_recv(ISO15765, out, sizeof(out), &olen, 500);
    tp_close();

    printf("scenario %s\n", name);
    printf("desc %s\n", desc);
    printf("req ");
    print_hex(req, n);
    printf("\n");
    put_frames("tx", f_tx, n_tx);
    put_frames("flow_in", f_in, n_in);
    put_frames("flow_out", f_out, n_out);
    put_frames("rx", f_rx, n_rx);
    printf("reply ");
    print_hex(out, olen);
    printf("\nresult %d %d\nend\n\n", rs, rr);
}

int main(void)
{
    static const uint8_t keepalive[] = {0x3E, 0x00};
    static const uint8_t session[] = {0x10, 0x01};
    static const uint8_t read_vin[] = {0x22, 0xF1, 0x90};
    static const uint8_t write_vin[] = {
        0x2E, 0xF1, 0x90,
        'W', 'V', 'W', 'Z', 'Z', 'Z', '1', 'J', 'Z',
        'X', 'W', '0', '0', '0', '0', '0', '1'
    };
    static const uint8_t eight[] = {0x2E, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
    static const uint8_t nrc_range[] = {0x22, 0x12, 0x34};
    static const uint8_t pending[] = {0x11, 0x01};

    printf("# Konformitätskorpus ISO-TP — erzeugt von j2534/tools/golden.c\n");
    printf("# Quelle: C-Implementierung (src/transport_usb.c) gegen usb_sim.c\n");
    printf("# Rollen: tx = Tester->ECU beim Senden, flow_in = Flow Control des ECU\n");
    printf("#         flow_out = Flow Control des Testers beim Empfangen, rx = ECU->Tester\n");
    printf("# Format: <rolle> <can-id hex> <dlc> <daten hex>\n");
    printf("#         req = gesendete Nutzlast, reply = empfangene Nutzlast,\n");
    printf("#         result = Rückgabecodes (send recv), 0 = OK\n\n");

    scenario("tester_present", "Single Frame, 2 Byte, Padding 0xAA", keepalive, sizeof(keepalive));
    scenario("session_control", "Single Frame, 2 Byte, 6-Byte-Antwort", session, sizeof(session));
    scenario("read_vin_multiframe", "Anfrage 3 Byte, Antwort 20 Byte mit Flow Control",
             read_vin, sizeof(read_vin));
    scenario("write_vin_multiframe", "20 Byte senden (FF + 2 CF), 20 Byte Antwort",
             write_vin, sizeof(write_vin));
    scenario("send_eight_bytes", "8 Byte: Grenze zwischen Single und First Frame",
             eight, sizeof(eight));
    scenario("nrc_out_of_range", "Negativantwort 7F 22 31", nrc_range, sizeof(nrc_range));
    scenario("nrc_response_pending", "Negativantwort 7F 11 78", pending, sizeof(pending));
    return 0;
}
