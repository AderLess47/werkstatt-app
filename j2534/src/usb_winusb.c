/*
 * usb_winusb.c — WinUSB-Adapter (Windows).
 *
 * WICHTIG: Diese Datei wird auf dem Build-Host (Linux) nicht kompiliert. Sie ist
 * gegen die WinUSB-API geschrieben und muss auf einem Windows-Rechner mit
 * Windows SDK gebaut und getestet werden — siehe Spezifikation §8 (Status).
 *
 * Bewusste Entscheidungen:
 *  - WinUSB statt Kernel-Treiber: kein WHQL, keine Signatur eines .sys, keine
 *    Blue-Screens. Das Gerät braucht nur eine INF, die es an winusb.sys bindet.
 *  - Alle Transfers asynchron mit OVERLAPPED, damit ein Timeout sauber über
 *    CancelIoEx abgebrochen werden kann und nicht in der Pipe hängt.
 *  - Geräteerkennung über die Device-Interface-GUID aus der INF. VID/PID allein
 *    reicht nicht, weil CreateFile einen Interface-Pfad braucht.
 */
#include "usb_backend.h"

#ifdef _WIN32

#include <windows.h>
#include <winusb.h>
#include <setupapi.h>
#include <stdio.h>
#include <string.h>

/* Muss mit DeviceInterfaceGUIDs in der INF des Geräts übereinstimmen. */
static const GUID GUID_DEVINTERFACE_WERKSTATT_VCI =
    {0x8b1f6c42, 0x2d7a, 0x4c19, {0x9e, 0x53, 0x7a, 0x11, 0x0c, 0x6d, 0x44, 0x21}};

#define CTRL_REQUEST_VBATT   0x10
#define CTRL_REQUEST_VERSION 0x11
#define IO_TIMEOUT_MS        2000u

typedef struct {
    HANDLE handle;
    WINUSB_INTERFACE_HANDLE iface;
    UCHAR pipe_in;
    UCHAR pipe_out;
    ULONG packet_size;
} win_dev;

/* Sucht den ersten Interface-Pfad des Geräts. 1 = gefunden. */
static int find_interface_path(char *path, DWORD cap)
{
    HDEVINFO set;
    SP_DEVICE_INTERFACE_DATA ifdata;
    DWORD index = 0;

    set = SetupDiGetClassDevsA(&GUID_DEVINTERFACE_WERKSTATT_VCI, NULL, NULL,
                               DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (set == INVALID_HANDLE_VALUE) {
        return 0;
    }
    ifdata.cbSize = sizeof(ifdata);
    while (SetupDiEnumDeviceInterfaces(set, NULL, &GUID_DEVINTERFACE_WERKSTATT_VCI,
                                      index, &ifdata)) {
        DWORD needed = 0;
        PSP_DEVICE_INTERFACE_DETAIL_DATA_A detail;
        SetupDiGetDeviceInterfaceDetailA(set, &ifdata, NULL, 0, &needed, NULL);
        if (needed == 0) {
            break;
        }
        detail = (PSP_DEVICE_INTERFACE_DETAIL_DATA_A)HeapAlloc(GetProcessHeap(), 0, needed);
        if (detail == NULL) {
            break;
        }
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_A);
        if (SetupDiGetDeviceInterfaceDetailA(set, &ifdata, detail, needed, &needed, NULL)) {
            _snprintf_s(path, cap, _TRUNCATE, "%s", detail->DevicePath);
            HeapFree(GetProcessHeap(), 0, detail);
            SetupDiDestroyDeviceInfoList(set);
            return 1;
        }
        HeapFree(GetProcessHeap(), 0, detail);
        index++;
    }
    SetupDiDestroyDeviceInfoList(set);
    return 0;
}

static int winusb_open(void **dev, char *err, int cap)
{
    char path[MAX_PATH];
    win_dev *d;
    HANDLE h;
    USB_INTERFACE_DESCRIPTOR idesc;
    ULONG i;

    if (!find_interface_path(path, sizeof(path))) {
        if (err && cap > 0) {
            snprintf(err, (size_t)cap, "VCI nicht gefunden (Interface-GUID/INF prüfen)");
        }
        return TP_E_NOTFOUND;
    }
    h = CreateFileA(path, GENERIC_READ | GENERIC_WRITE,
                    FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        if (err && cap > 0) {
            snprintf(err, (size_t)cap, "CreateFile fehlgeschlagen (%lu)", (unsigned long)GetLastError());
        }
        return TP_E_IO;
    }
    d = (win_dev *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(*d));
    if (d == NULL) {
        CloseHandle(h);
        return TP_E_IO;
    }
    d->handle = h;
    if (!WinUsb_Initialize(h, &d->iface)) {
        CloseHandle(h);
        HeapFree(GetProcessHeap(), 0, d);
        if (err && cap > 0) {
            snprintf(err, (size_t)cap, "WinUsb_Initialize fehlgeschlagen (%lu)",
                     (unsigned long)GetLastError());
        }
        return TP_E_IO;
    }
    if (!WinUsb_QueryInterfaceSettings(d->iface, 0, &idesc) || idesc.bNumEndpoints == 0) {
        WinUsb_Free(d->iface);
        CloseHandle(h);
        HeapFree(GetProcessHeap(), 0, d);
        return TP_E_IO;
    }
    for (i = 0; i < idesc.bNumEndpoints; i++) {
        WINUSB_PIPE_INFORMATION pipe;
        if (!WinUsb_QueryPipe(d->iface, 0, (UCHAR)i, &pipe)) {
            continue;
        }
        if (pipe.PipeType != UsbdPipeTypeBulk) {
            continue;
        }
        if ((pipe.PipeId & 0x80) != 0) {
            d->pipe_in = pipe.PipeId;
        } else {
            d->pipe_out = pipe.PipeId;
            d->packet_size = pipe.MaximumPacketSize;
        }
    }
    if (d->pipe_in == 0 || d->pipe_out == 0) {
        WinUsb_Free(d->iface);
        CloseHandle(h);
        HeapFree(GetProcessHeap(), 0, d);
        if (err && cap > 0) {
            snprintf(err, (size_t)cap, "keine Bulk-Endpunkte gefunden");
        }
        return TP_E_IO;
    }
    {
        /* Stall automatisch räumen, sonst hängt die Pipe nach einem Fehler. */
        UCHAR enable = 1;
        WinUsb_SetPipePolicy(d->iface, d->pipe_in, AUTO_CLEAR_STALL, sizeof(enable), &enable);
        WinUsb_SetPipePolicy(d->iface, d->pipe_out, AUTO_CLEAR_STALL, sizeof(enable), &enable);
        WinUsb_SetPipePolicy(d->iface, d->pipe_out, SHORT_PACKET_TERMINATE, sizeof(enable), &enable);
        WinUsb_FlushPipe(d->iface, d->pipe_in);
        WinUsb_FlushPipe(d->iface, d->pipe_out);
    }
    *dev = d;
    return TP_OK;
}

static void winusb_close(void *dev)
{
    win_dev *d = (win_dev *)dev;
    if (d == NULL) {
        return;
    }
    WinUsb_Free(d->iface);
    CloseHandle(d->handle);
    HeapFree(GetProcessHeap(), 0, d);
}

static int transfer(win_dev *d, int is_read, uint8_t *buf, ULONG len,
                    ULONG *done, ULONG timeout_ms)
{
    OVERLAPPED ov;
    HANDLE ev;
    BOOL ok;
    DWORD wait;

    memset(&ov, 0, sizeof(ov));
    ev = CreateEventA(NULL, TRUE, FALSE, NULL);
    if (ev == NULL) {
        return TP_E_IO;
    }
    ov.hEvent = ev;
    *done = 0;

    if (is_read) {
        ok = WinUsb_ReadPipe(d->iface, d->pipe_in, buf, len, done, &ov);
    } else {
        ok = WinUsb_WritePipe(d->iface, d->pipe_out, buf, len, done, &ov);
    }
    if (!ok) {
        DWORD e = GetLastError();
        if (e != ERROR_IO_PENDING) {
            CloseHandle(ev);
            return (e == ERROR_SEM_TIMEOUT) ? TP_E_TIMEOUT : TP_E_IO;
        }
    }
    wait = WaitForSingleObject(ev, timeout_ms);
    if (wait != WAIT_OBJECT_0) {
        CancelIoEx(d->handle, &ov);
        WaitForSingleObject(ev, 500);
        CloseHandle(ev);
        return TP_E_TIMEOUT;
    }
    if (!WinUsb_GetOverlappedResult(d->iface, &ov, done, FALSE)) {
        DWORD e = GetLastError();
        CloseHandle(ev);
        return (e == ERROR_OPERATION_ABORTED || e == ERROR_SEM_TIMEOUT) ? TP_E_TIMEOUT : TP_E_IO;
    }
    CloseHandle(ev);
    return TP_OK;
}

static int winusb_write(void *dev, const uint8_t *buf, uint32_t len, uint32_t timeout_ms)
{
    win_dev *d = (win_dev *)dev;
    ULONG done = 0;
    int rc = transfer(d, 0, (uint8_t *)buf, len, &done, timeout_ms ? timeout_ms : IO_TIMEOUT_MS);
    if (rc != TP_OK) {
        return rc;
    }
    return (done == len) ? TP_OK : TP_E_IO;
}

static int winusb_read(void *dev, uint8_t *buf, uint32_t cap, uint32_t *got, uint32_t timeout_ms)
{
    win_dev *d = (win_dev *)dev;
    ULONG done = 0;
    int rc = transfer(d, 1, buf, cap, &done, timeout_ms ? timeout_ms : IO_TIMEOUT_MS);
    *got = done;
    return rc;
}

static int winusb_control(win_dev *d, uint8_t request, uint8_t *buf, uint32_t len)
{
    WINUSB_SETUP_PACKET pkt;
    ULONG done = 0;

    pkt.RequestType = 0xC0; /* vendor, device-to-host */
    pkt.Request = request;
    pkt.Value = 0;
    pkt.Index = 0;
    pkt.Length = (USHORT)len;
    if (!WinUsb_ControlTransfer(d->iface, pkt, buf, len, &done, NULL) || done != len) {
        return TP_E_IO;
    }
    return TP_OK;
}

static int winusb_vbat(void *dev, uint32_t *mv)
{
    uint8_t b[4];
    if (mv == NULL) {
        return TP_E_ARG;
    }
    if (winusb_control((win_dev *)dev, CTRL_REQUEST_VBATT, b, 4) != TP_OK) {
        return TP_E_IO;
    }
    *mv = (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
    return TP_OK;
}

static uint32_t winusb_firmware(void *dev)
{
    uint8_t b[4];
    if (winusb_control((win_dev *)dev, CTRL_REQUEST_VERSION, b, 4) != TP_OK) {
        return 0;
    }
    return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

const usb_backend winusb_backend = {
    winusb_open, winusb_close, winusb_write, winusb_read, winusb_vbat, winusb_firmware, "winusb-vci"
};

#else /* !_WIN32 */

typedef int usb_winusb_unused_translation_unit; /* leere TU vermeiden */

#endif /* _WIN32 */
