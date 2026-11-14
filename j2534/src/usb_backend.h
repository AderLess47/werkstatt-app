/*
 * usb_backend.h — die einzige Schnittstelle zwischen Transport und Hardware.
 *
 * Alles oberhalb (Framing, ISO-TP, Timeouts, Wiederverbinden) ist portabel und
 * wird gegen ein simuliertes Gerät getestet. Alles darunter sind ~250 Zeilen
 * Adapter pro Plattform (WinUSB, libusb, BLE).
 *
 * Rückgabewerte: 0 = ok, negativer Wert = Fehler (TP_E_*).
 */
#ifndef USB_BACKEND_H
#define USB_BACKEND_H

#include <stdint.h>

#define TP_OK               0
#define TP_E_NOTFOUND      -1   /* kein Gerät gefunden */
#define TP_E_IO            -2   /* Transportfehler (Kabel, Pipe-Stall) */
#define TP_E_TIMEOUT       -3   /* Zeit abgelaufen */
#define TP_E_DISCONNECTED  -4   /* Gerät verschwunden */
#define TP_E_TOOBIG        -5   /* Nutzlast größer als das Protokoll erlaubt */
#define TP_E_PROTO         -6   /* Rahmenfehler (CRC, Länge) */
#define TP_E_ARG           -7

typedef struct {
    /* Gerät öffnen; *dev ist opak. err darf NULL sein. */
    int  (*open)(void **dev, char *err, int errcap);
    void (*close)(void *dev);

    /* bulk out/in. timeout in ms. Bei *got == 0 und Ablauf: TP_E_TIMEOUT. */
    int  (*write)(void *dev, const uint8_t *buf, uint32_t len, uint32_t timeout_ms);
    int  (*read)(void *dev, uint8_t *buf, uint32_t cap, uint32_t *got, uint32_t timeout_ms);

    /* Versorgungsspannung am OBD-Stecker in mV (0x00 wenn nicht messbar). */
    int  (*vbat_mv)(void *dev, uint32_t *mv);

    /* Firmware-Version der Schnittstelle (0 wenn unbekannt). */
    uint32_t (*firmware)(void *dev);

    /* Menschenlesbarer Name für das Log. */
    const char *name;
} usb_backend;

#endif /* USB_BACKEND_H */
