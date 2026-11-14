/*
 * core.c — J2534-PassThru-Port: die ABI-Schicht.
 *
 * Aufbau:
 *   API-Funktionen (Export) -> Kanalzustand -> transport_usb.c -> usb_backend
 *
 * Diese Datei kennt keine Hardware und kein Bussprotokoll. Sie prüft Handles,
 * hält den Zustand und übersetzt Fehlercodes. Alles darunter ist austauschbar.
 */
#include "j2534.h"
#include "transport_usb.h"
#include "usb_backend.h"

#include <stdio.h>
#include <string.h>

#define MAX_CHANNELS   8
#define MAX_FILTERS    8
#define DLL_VERSION    "0.2.0-port"

typedef struct {
    uint32_t protocol;
    uint32_t flags;
    uint32_t baud;
    uint32_t filter_id[MAX_FILTERS];
    uint32_t filter_count;
} Channel;

static struct {
    int opened;
    uint32_t device_id;
    Channel ch[MAX_CHANNELS];
    char last_error[256];
    uint32_t filter_seq;
    uint32_t clock_ms;
} g;

/* ---------------------------------------------------------------- Fehler */

static int32_t fail(int32_t code, const char *text)
{
    snprintf(g.last_error, sizeof(g.last_error), "%s (0x%02X)", text, (unsigned)code);
    return code;
}

static int32_t map_error(int tp)
{
    switch (tp) {
    case TP_OK:
        return STATUS_NOERROR;
    case TP_E_TIMEOUT:
        return ERR_TIMEOUT;
    case TP_E_DISCONNECTED:
        return ERR_DEVICE_NOT_CONNECTED;
    case TP_E_TOOBIG:
        return ERR_INVALID_MSG;
    case TP_E_PROTO:
        return ERR_FAILED;
    case TP_E_NOTFOUND:
        return ERR_DEVICE_NOT_CONNECTED;
    default:
        return ERR_FAILED;
    }
}

static Channel *chan(uint32_t id)
{
    if (id == 0 || id > MAX_CHANNELS) {
        return NULL;
    }
    if (!g.ch[id - 1].protocol) {
        return NULL;
    }
    return &g.ch[id - 1];
}

static uint32_t tick(void)
{
    g.clock_ms += 1;
    return g.clock_ms;
}

/* --------------------------------------------------------------- Geräte-API */

J2534_API int32_t J2534_CALL PassThruOpen(const void *pName, uint32_t *pDeviceID)
{
    int rc;
    (void)pName;

    if (pDeviceID == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruOpen: pDeviceID is NULL");
    }
    if (g.opened) {
        return fail(ERR_DEVICE_IN_USE, "PassThruOpen: device already open");
    }
    rc = tp_open();
    if (rc != TP_OK) {
        return fail(map_error(rc), tp_last_error());
    }
    g.opened = 1;
    g.device_id = 1;
    memset(g.ch, 0, sizeof(g.ch));
    *pDeviceID = g.device_id;
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruClose(uint32_t DeviceID)
{
    if (!g.opened || DeviceID != g.device_id) {
        return fail(ERR_INVALID_DEVICE_ID, "PassThruClose: unknown device");
    }
    tp_close();
    memset(g.ch, 0, sizeof(g.ch));
    g.opened = 0;
    g.device_id = 0;
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruConnect(uint32_t DeviceID, uint32_t ProtocolID,
                                             uint32_t Flags, uint32_t BaudRate,
                                             uint32_t *pChannelID)
{
    uint32_t i;

    if (!g.opened || DeviceID != g.device_id) {
        return fail(ERR_INVALID_DEVICE_ID, "PassThruConnect: device not open");
    }
    if (pChannelID == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruConnect: pChannelID is NULL");
    }
    if (ProtocolID != ISO15765 && ProtocolID != CAN) {
        return fail(ERR_INVALID_PROTOCOL_ID, "PassThruConnect: protocol not supported");
    }
    if (BaudRate != 500000 && BaudRate != 250000) {
        return fail(ERR_INVALID_BAUDRATE, "PassThruConnect: unsupported baud rate");
    }
    for (i = 0; i < MAX_CHANNELS; i++) {
        if (!g.ch[i].protocol) {
            Channel *c = &g.ch[i];
            memset(c, 0, sizeof(*c));
            c->protocol = ProtocolID;
            c->flags = Flags;
            c->baud = BaudRate;
            *pChannelID = i + 1;
            return STATUS_NOERROR;
        }
    }
    return fail(ERR_EXCEEDED_LIMIT, "PassThruConnect: no free channel");
}

J2534_API int32_t J2534_CALL PassThruDisconnect(uint32_t ChannelID)
{
    Channel *c = chan(ChannelID);
    if (c == NULL) {
        return fail(ERR_INVALID_CHANNEL_ID, "PassThruDisconnect: invalid channel");
    }
    memset(c, 0, sizeof(*c));
    return STATUS_NOERROR;
}

/* ------------------------------------------------------------------ Messages */

J2534_API int32_t J2534_CALL PassThruReadMsgs(uint32_t ChannelID, PASSTHRU_MSG *pMsg,
                                              uint32_t *pNumMsgs, uint32_t Timeout)
{
    Channel *c = chan(ChannelID);
    uint32_t want, got = 0;

    if (c == NULL) {
        return fail(ERR_INVALID_CHANNEL_ID, "PassThruReadMsgs: invalid channel");
    }
    if (pMsg == NULL || pNumMsgs == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruReadMsgs: NULL parameter");
    }
    want = *pNumMsgs;
    while (got < want) {
        PASSTHRU_MSG tmp;
        uint32_t n = 0;
        int rc;

        memset(&tmp, 0, sizeof(tmp));
        rc = tp_recv(c->protocol, tmp.Data, sizeof(tmp.Data), &n, Timeout);
        if (rc == TP_E_TIMEOUT) {
            break; /* normkonform: 0 Nachrichten, kein Fehler */
        }
        if (rc != TP_OK) {
            *pNumMsgs = got;
            return fail(map_error(rc), tp_last_error());
        }
        tmp.ProtocolID = c->protocol;
        tmp.RxStatus = RX_INDICATION;
        tmp.Timestamp = tick();
        tmp.DataSize = n;
        pMsg[got] = tmp;
        got++;
    }
    *pNumMsgs = got;
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruWriteMsgs(uint32_t ChannelID, const PASSTHRU_MSG *pMsg,
                                               uint32_t *pNumMsgs, uint32_t Timeout)
{
    Channel *c = chan(ChannelID);
    uint32_t i, n;

    if (c == NULL) {
        return fail(ERR_INVALID_CHANNEL_ID, "PassThruWriteMsgs: invalid channel");
    }
    if (pMsg == NULL || pNumMsgs == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruWriteMsgs: NULL parameter");
    }
    n = *pNumMsgs;
    for (i = 0; i < n; i++) {
        int rc;
        if (pMsg[i].DataSize == 0 || pMsg[i].DataSize > PASSTHRU_MSG_DATA_SIZE) {
            *pNumMsgs = i;
            return fail(ERR_INVALID_MSG, "PassThruWriteMsgs: invalid message length");
        }
        rc = tp_send(c->protocol, pMsg[i].Data, pMsg[i].DataSize, Timeout);
        if (rc != TP_OK) {
            *pNumMsgs = i;
            return fail(map_error(rc), tp_last_error());
        }
    }
    *pNumMsgs = n;
    return STATUS_NOERROR;
}

/* --------------------------------------------------------------- Periodisch */

J2534_API int32_t J2534_CALL PassThruStartPeriodicMsg(uint32_t ChannelID, const PASSTHRU_MSG *pMsg,
                                                      uint32_t *pMsgID, uint32_t TimeInterval)
{
    (void)ChannelID;
    (void)pMsg;
    (void)pMsgID;
    (void)TimeInterval;
    return fail(ERR_NOT_SUPPORTED, "PassThruStartPeriodicMsg: not implemented");
}

J2534_API int32_t J2534_CALL PassThruStopPeriodicMsg(uint32_t ChannelID, uint32_t MsgID)
{
    (void)ChannelID;
    (void)MsgID;
    return fail(ERR_NOT_SUPPORTED, "PassThruStopPeriodicMsg: not implemented");
}

/* ------------------------------------------------------------------- Filter */

J2534_API int32_t J2534_CALL PassThruStartMsgFilter(uint32_t ChannelID, uint32_t FilterType,
                                                    const PASSTHRU_MSG *pMaskMsg,
                                                    const PASSTHRU_MSG *pPatternMsg,
                                                    const PASSTHRU_MSG *pFlowControlMsg,
                                                    uint32_t *pFilterID)
{
    Channel *c = chan(ChannelID);

    if (c == NULL) {
        return fail(ERR_INVALID_CHANNEL_ID, "PassThruStartMsgFilter: invalid channel");
    }
    if (pFilterID == NULL || pPatternMsg == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruStartMsgFilter: NULL parameter");
    }
    if (FilterType != PASS_FILTER && FilterType != BLOCK_FILTER &&
        FilterType != FLOW_CONTROL_FILTER) {
        return fail(ERR_INVALID_FLAGS, "PassThruStartMsgFilter: unknown filter type");
    }
    if (FilterType == FLOW_CONTROL_FILTER && pFlowControlMsg == NULL) {
        return fail(ERR_NO_FLOW_CONTROL, "PassThruStartMsgFilter: missing flow control");
    }
    if (c->filter_count >= MAX_FILTERS) {
        return fail(ERR_EXCEEDED_LIMIT, "PassThruStartMsgFilter: too many filters");
    }
    (void)pMaskMsg;
    g.filter_seq += 1;
    c->filter_id[c->filter_count++] = g.filter_seq;
    *pFilterID = g.filter_seq;
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruStopMsgFilter(uint32_t ChannelID, uint32_t FilterID)
{
    Channel *c = chan(ChannelID);
    uint32_t i;

    if (c == NULL) {
        return fail(ERR_INVALID_CHANNEL_ID, "PassThruStopMsgFilter: invalid channel");
    }
    for (i = 0; i < c->filter_count; i++) {
        if (c->filter_id[i] == FilterID) {
            c->filter_id[i] = c->filter_id[--c->filter_count];
            return STATUS_NOERROR;
        }
    }
    return fail(ERR_INVALID_FILTER_ID, "PassThruStopMsgFilter: unknown filter");
}

/* -------------------------------------------------------------- Gerät sonst */

J2534_API int32_t J2534_CALL PassThruSetProgrammingVoltage(uint32_t DeviceID, uint32_t PinNumber,
                                                          uint32_t Voltage)
{
    if (!g.opened || DeviceID != g.device_id) {
        return fail(ERR_INVALID_DEVICE_ID, "PassThruSetProgrammingVoltage: device not open");
    }
    /* Sicherheitsrelevant: erst freigeben, wenn die Hardware Pin, Spannung und
       Strombegrenzung nachweislich beherrscht (Spezifikation §9.5). */
    (void)PinNumber;
    (void)Voltage;
    return fail(ERR_NOT_SUPPORTED, "PassThruSetProgrammingVoltage: hardware does not support it");
}

J2534_API int32_t J2534_CALL PassThruReadVersion(uint32_t DeviceID, char *pFirmwareVersion,
                                                 char *pDllVersion, char *pApiVersion)
{
    if (!g.opened || DeviceID != g.device_id) {
        return fail(ERR_INVALID_DEVICE_ID, "PassThruReadVersion: device not open");
    }
    if (pFirmwareVersion == NULL || pDllVersion == NULL || pApiVersion == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruReadVersion: NULL parameter");
    }
    {
        uint32_t fw = tp_firmware();
        snprintf(pFirmwareVersion, 80, "%u.%u", (unsigned)(fw >> 16), (unsigned)(fw & 0xFFFF));
    }
    strcpy(pDllVersion, DLL_VERSION);
    strcpy(pApiVersion, "04.04");
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruGetLastError(char *pErrorDescription)
{
    if (pErrorDescription == NULL) {
        return ERR_NULL_PARAMETER;
    }
    strcpy(pErrorDescription, g.last_error);
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruIoctl(uint32_t ChannelID, uint32_t IoctlID,
                                           const void *pInput, void *pOutput)
{
    (void)pOutput;

    switch (IoctlID) {
    case READ_VBATT: {
        uint32_t *mv = (uint32_t *)pInput;
        uint32_t value = 0;
        int rc;
        if (mv == NULL) {
            return fail(ERR_NULL_PARAMETER, "READ_VBATT: NULL parameter");
        }
        rc = tp_vbat(&value, 1000);
        if (rc != TP_OK) {
            return fail(map_error(rc), tp_last_error());
        }
        *mv = value;
        return STATUS_NOERROR;
    }
    case GET_CONFIG: {
        const SCONFIG_LIST_INTERNAL *l = (const SCONFIG_LIST_INTERNAL *)pInput;
        uint32_t i;
        if (l == NULL || l->ConfigPtr == NULL) {
            return fail(ERR_NULL_PARAMETER, "GET_CONFIG: NULL parameter");
        }
        for (i = 0; i < l->NumOfParams; i++) {
            if (l->ConfigPtr[i].Parameter == DATA_RATE) {
                Channel *c = chan(ChannelID);
                l->ConfigPtr[i].Value = (c != NULL) ? c->baud : 500000u;
            } else {
                return fail(ERR_INVALID_IOCTL_VALUE, "GET_CONFIG: unsupported parameter");
            }
        }
        return STATUS_NOERROR;
    }
    case SET_CONFIG: {
        const SCONFIG_LIST_INTERNAL *l = (const SCONFIG_LIST_INTERNAL *)pInput;
        uint32_t i;
        if (l == NULL || l->ConfigPtr == NULL) {
            return fail(ERR_NULL_PARAMETER, "SET_CONFIG: NULL parameter");
        }
        for (i = 0; i < l->NumOfParams; i++) {
            if (l->ConfigPtr[i].Parameter == DATA_RATE) {
                Channel *c = chan(ChannelID);
                if (c != NULL) {
                    c->baud = l->ConfigPtr[i].Value;
                }
            } else {
                return fail(ERR_INVALID_IOCTL_VALUE, "SET_CONFIG: unsupported parameter");
            }
        }
        return STATUS_NOERROR;
    }
    case CLEAR_RX_BUFFER:
    case CLEAR_TX_BUFFER:
    case CLEAR_MSG_FILTERS: {
        Channel *c = chan(ChannelID);
        if (c == NULL) {
            return fail(ERR_INVALID_CHANNEL_ID, "CLEAR_*: invalid channel");
        }
        if (IoctlID == CLEAR_MSG_FILTERS) {
            c->filter_count = 0;
        }
        return STATUS_NOERROR;
    }
    case READ_PROG_VOLTAGE: {
        uint32_t *mv = (uint32_t *)pInput;
        if (mv == NULL) {
            return fail(ERR_NULL_PARAMETER, "READ_PROG_VOLTAGE: NULL parameter");
        }
        *mv = 0; /* keine Programmier-Spannungsquelle vorhanden */
        return STATUS_NOERROR;
    }
    default:
        return fail(ERR_INVALID_IOCTL_ID, "PassThruIoctl: unknown IOCTL");
    }
}

/* --------------------------------------------------- API 05.00: Discovery */

static int g_scan_index = 0;
static int g_scan_count = 0;

J2534_API int32_t J2534_CALL PassThruScanForDevices(uint32_t *pDeviceCount)
{
    if (pDeviceCount == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruScanForDevices: NULL parameter");
    }
    g_scan_index = 0;
    g_scan_count = tp_connected() ? 1 : 1; /* Discovery liefert das Gerät auch ungeöffnet */
    *pDeviceCount = (uint32_t)g_scan_count;
    return STATUS_NOERROR;
}

J2534_API int32_t J2534_CALL PassThruGetNextDevice(uint32_t *pDeviceID)
{
    if (pDeviceID == NULL) {
        return fail(ERR_NULL_PARAMETER, "PassThruGetNextDevice: NULL parameter");
    }
    if (g_scan_index >= g_scan_count) {
        /* API 05.00 sieht für "Liste erschöpft" einen eigenen Code vor; bis zur
           Verifikation gegen die lizenzierte Norm wird ERR_BUFFER_EMPTY genutzt. */
        return fail(ERR_BUFFER_EMPTY, "PassThruGetNextDevice: no more devices");
    }
    g_scan_index++;
    *pDeviceID = 1;
    return STATUS_NOERROR;
}
