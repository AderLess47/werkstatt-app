/*
 * j2534.h — eigener J2534-PassThru-Port für die Werkstatt-App
 *
 * Ziel: eine DLL (Windows) bzw. Shared Library (Linux/macOS), die den
 * SAE-J2534-1-Funktionsumfang exportiert und intern auf unsere
 * Transport-Schicht (eigene Hardware, Hersteller-SDK, DoIP) abbildet.
 *
 * WICHTIG zur ABI:
 * In der Norm sind alle Felder "unsigned long". Auf Win32/x64 ist das 32 Bit,
 * auf LP64-Systemen (Linux, macOS) aber 64 Bit. Damit die Strukturen überall
 * gleich liegen, verwendet dieser Header durchgehend uint32_t/int32_t.
 *
 * Referenz: SAE J2534-1 (API 04.04 und 05.00), J2534-2 (optionale Features).
 * Die Werte der Ioctl-/Config-Konstanten sind gegen die Norm zu verifizieren,
 * sobald eine lizenzierte Ausgabe vorliegt (siehe docs/j2534-dll-spezifikation.md).
 */
#ifndef J2534_H
#define J2534_H

#include <stdint.h>

#ifdef _WIN32
#  define J2534_API  __declspec(dllexport)
#  define J2534_CALL __stdcall
#else
#  define J2534_API  __attribute__((visibility("default")))
#  define J2534_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ Status */
#define STATUS_NOERROR              0x00
#define ERR_NOT_SUPPORTED           0x01
#define ERR_INVALID_CHANNEL_ID      0x02
#define ERR_INVALID_PROTOCOL_ID     0x03
#define ERR_NULL_PARAMETER          0x04
#define ERR_INVALID_IOCTL_VALUE     0x05
#define ERR_INVALID_FLAGS           0x06
#define ERR_FAILED                  0x07
#define ERR_DEVICE_NOT_CONNECTED    0x08
#define ERR_TIMEOUT                 0x09
#define ERR_INVALID_MSG             0x0A
#define ERR_INVALID_TIME_INTERVAL   0x0B
#define ERR_EXCEEDED_LIMIT          0x0C
#define ERR_INVALID_MSG_ID          0x0D
#define ERR_DEVICE_IN_USE           0x0E
#define ERR_INVALID_IOCTL_ID        0x0F
#define ERR_BUFFER_EMPTY            0x10
#define ERR_BUFFER_FULL             0x11
#define ERR_BUFFER_OVERFLOW         0x12
#define ERR_PIN_INVALID             0x13
#define ERR_CHANNEL_IN_USE          0x14
#define ERR_MSG_PROTOCOL_ID         0x15
#define ERR_INVALID_FILTER_ID       0x16
#define ERR_NO_FLOW_CONTROL         0x17
#define ERR_NOT_UNIQUE              0x18
#define ERR_INVALID_BAUDRATE        0x19
#define ERR_INVALID_DEVICE_ID       0x1A

/* --------------------------------------------------------------- Protokolle */
#define J1850VPW          0x01
#define J1850PWM          0x02
#define ISO9141           0x03
#define ISO14230          0x04
#define CAN               0x05
#define ISO15765          0x06
#define SCI_A_ENGINE      0x07
#define SCI_A_TRANS       0x08
#define SCI_B_ENGINE      0x09
#define SCI_B_TRANS       0x0A

/* J2534-2: Pin-Selection-Varianten (Kanal auf andere Pins umschaltbar) */
#define J1850VPW_PS       0x8000
#define J1850PWM_PS       0x8001
#define ISO9141_PS        0x8002
#define ISO14230_PS       0x8003
#define CAN_PS            0x8004
#define ISO15765_PS       0x8005

/* J2534-2: mehrere Kanäle gleichzeitig */
#define CAN_CH1           0x9000
#define ISO15765_CH1      0x9400

/* ------------------------------------------------------------------- Flags */
#define CAN_29BIT_ID      0x00000100
#define ISO9141_NO_CHECKSUM 0x00000200
#define CAN_ID_BOTH       0x00000800
#define ISO9141_K_LINE_ONLY 0x00001000

/* -------------------------------------------------------------- Rx-Status */
#define TX_MSG_TYPE         0x00000001
#define START_OF_MESSAGE    0x00000002
#define ISO15765_FIRST_FRAME 0x00000002
#define RX_INDICATION       0x00000004
#define ISO15765_TPCI_MASK  0x00000038
#define ISO15765_SINGLE_FRAME 0x00000000
#define ISO15765_CONSECUTIVE_FRAME 0x00000010
#define ISO15765_FLOW_CONTROL 0x00000020
#define CAN_29BIT_ID_RX     0x00000100

/* --------------------------------------------------------------- Filter */
#define PASS_FILTER           0x00000001
#define BLOCK_FILTER          0x00000002
#define FLOW_CONTROL_FILTER   0x00000003

/* ---------------------------------------------------------------- Ioctl */
#define GET_CONFIG            0x01
#define SET_CONFIG            0x02
#define READ_VBATT            0x03
#define FIVE_BAUD_INIT        0x04
#define FAST_INIT             0x05
#define CLEAR_TX_BUFFER       0x07
#define CLEAR_RX_BUFFER       0x08
#define CLEAR_PERIODIC_MSGS   0x09
#define CLEAR_MSG_FILTERS     0x0A
#define CLEAR_FUNCT_MSG_LOOKUP_TABLE 0x0B
#define ADD_TO_FUNCT_MSG_LOOKUP_TABLE 0x0C
#define DELETE_FROM_FUNCT_MSG_LOOKUP_TABLE 0x0D
#define READ_PROG_VOLTAGE     0x0E

/* ---------------------------------------------------------- Config-Parameter
 * Nur die in diesem Port implementierten Werte. Der vollständige Satz steht in
 * SAE J2534-1 §8.5; vor dem ersten Release ergänzen. */
#define DATA_RATE             0x01
#define LOOPBACK              0x03
#define ISO15765_BS           0x1E
#define ISO15765_STMIN        0x1F

/* -------------------------------------------------------------- Strukturen */
#define PASSTHRU_MSG_DATA_SIZE 4128

typedef struct {
    uint32_t ProtocolID;
    uint32_t RxStatus;
    uint32_t TxFlags;
    uint32_t Timestamp;
    uint32_t DataSize;
    uint32_t ExtraDataIndex;
    unsigned char Data[PASSTHRU_MSG_DATA_SIZE];
} PASSTHRU_MSG;

typedef struct {
    uint32_t Parameter;
    uint32_t Value;
} SCONFIG;

typedef struct {
    uint32_t Parameter;
    uint32_t Value;
} SCONFIG_LIST;

typedef struct {
    uint32_t NumOfParams;
    SCONFIG *ConfigPtr;
} SCONFIG_LIST_INTERNAL;

/* ------------------------------------------------------------- Funktionen */
J2534_API int32_t J2534_CALL PassThruOpen(const void *pName, uint32_t *pDeviceID);
J2534_API int32_t J2534_CALL PassThruClose(uint32_t DeviceID);

J2534_API int32_t J2534_CALL PassThruConnect(uint32_t DeviceID, uint32_t ProtocolID,
                                             uint32_t Flags, uint32_t BaudRate,
                                             uint32_t *pChannelID);
J2534_API int32_t J2534_CALL PassThruDisconnect(uint32_t ChannelID);

J2534_API int32_t J2534_CALL PassThruReadMsgs(uint32_t ChannelID, PASSTHRU_MSG *pMsg,
                                              uint32_t *pNumMsgs, uint32_t Timeout);
J2534_API int32_t J2534_CALL PassThruWriteMsgs(uint32_t ChannelID, const PASSTHRU_MSG *pMsg,
                                               uint32_t *pNumMsgs, uint32_t Timeout);

J2534_API int32_t J2534_CALL PassThruStartPeriodicMsg(uint32_t ChannelID, const PASSTHRU_MSG *pMsg,
                                                      uint32_t *pMsgID, uint32_t TimeInterval);
J2534_API int32_t J2534_CALL PassThruStopPeriodicMsg(uint32_t ChannelID, uint32_t MsgID);

J2534_API int32_t J2534_CALL PassThruStartMsgFilter(uint32_t ChannelID, uint32_t FilterType,
                                                    const PASSTHRU_MSG *pMaskMsg,
                                                    const PASSTHRU_MSG *pPatternMsg,
                                                    const PASSTHRU_MSG *pFlowControlMsg,
                                                    uint32_t *pFilterID);
J2534_API int32_t J2534_CALL PassThruStopMsgFilter(uint32_t ChannelID, uint32_t FilterID);

J2534_API int32_t J2534_CALL PassThruSetProgrammingVoltage(uint32_t DeviceID, uint32_t PinNumber,
                                                          uint32_t Voltage);
J2534_API int32_t J2534_CALL PassThruReadVersion(uint32_t DeviceID, char *pFirmwareVersion,
                                                 char *pDllVersion, char *pApiVersion);
J2534_API int32_t J2534_CALL PassThruGetLastError(char *pErrorDescription);
J2534_API int32_t J2534_CALL PassThruIoctl(uint32_t ChannelID, uint32_t IoctlID,
                                           const void *pInput, void *pOutput);

/* API 05.00: dynamische Geräteerkennung */
J2534_API int32_t J2534_CALL PassThruScanForDevices(uint32_t *pDeviceCount);
J2534_API int32_t J2534_CALL PassThruGetNextDevice(uint32_t *pDeviceID);

#ifdef __cplusplus
}
#endif

#endif /* J2534_H */
