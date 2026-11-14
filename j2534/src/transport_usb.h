/*
 * transport_usb.h — Schnittstelle des Transports zur J2534-API-Schicht.
 */
#ifndef TRANSPORT_USB_H
#define TRANSPORT_USB_H

#include <stdint.h>
#include "usb_backend.h"

uint16_t tp_crc16(const uint8_t *p, uint32_t n);

void tp_set_backend(const usb_backend *be);   /* vor tp_open setzen (Tests) */
int  tp_open(void);
void tp_close(void);
int  tp_connected(void);

int  tp_send(uint32_t protocol, const uint8_t *data, uint32_t n, uint32_t timeout_ms);
int  tp_recv(uint32_t protocol, uint8_t *out, uint32_t cap, uint32_t *out_len, uint32_t timeout_ms);
int  tp_vbat(uint32_t *mv, uint32_t timeout_ms);
uint32_t tp_firmware(void);
const char *tp_name(void);
const char *tp_last_error(void);
uint32_t tp_millis(void);   /* monotone Millisekunden für Latenzmessung */

/* Diagnose für den VCI-Checker und für Tests */
uint32_t tp_resync_count(void);
uint32_t tp_crc_error_count(void);

#endif /* TRANSPORT_USB_H */
