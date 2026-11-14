package de.werkstatt.vci.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.VciException

/**
 * USB-Host-Transport zum VCI.
 *
 * Android spricht hier dasselbe Drahtformat wie die J2534-DLL unter Windows
 * (`Wire`), nur über `UsbDeviceConnection.bulkTransfer` statt WinUSB/libusb.
 * Der Dongle muss also nichts Neues lernen — die Firmware ist dieselbe.
 *
 * Was dieser Transport **nicht** tut: CAN-Rahmen filtern. Der Rust-Kern prüft
 * die Kennungen selbst (`isotp.rs`); doppelte Filterlogik an zwei Stellen wäre
 * die nächste Fehlerquelle.
 */
class UsbCanTransport private constructor(
    private val verbindung: UsbDeviceConnection,
    private val schnittstelle: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : CanTransport, AutoCloseable {

    private val leser = RahmenLeser()
    private val schreibSperre = Any()
    private val puffer = ByteArray(4096)

    override fun send(frame: CanFrameFfi) {
        val nutzlast = Wire.canNutzlast(frame.id, frame.data, frame.dlc.toInt())
        val bytes = Wire.rahmen(Wire.T_CAN, nutzlast)
        val n = synchronized(schreibSperre) {
            verbindung.bulkTransfer(epOut, bytes, bytes.size, SCHREIB_TIMEOUT_MS)
        }
        if (n < 0) throw VciException.Io()
    }

    override fun recv(timeoutMs: UInt): CanFrameFfi? {
        val frist = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
        while (true) {
            val rahmen = leser.naechster()
            if (rahmen != null) {
                when (rahmen.typ) {
                    Wire.T_ERROR -> throw VciException.Protocol("Gerät meldet Fehler")
                    Wire.T_CAN -> {
                        val can = Wire.liesCan(rahmen.nutzlast) ?: continue
                        return CanFrameFfi(can.id, can.daten, can.dlc.toUByte())
                    }
                    else -> continue // Spannung/Version gehören nicht zum CAN-Strom
                }
            }

            val restMs = ((frist - System.nanoTime()) / 1_000_000L).toInt()
            if (restMs <= 0) return null
            val n = verbindung.bulkTransfer(epIn, puffer, puffer.size, restMs)
            if (n < 0) return null
            if (n > 0) leser.schiebe(puffer, n)
        }
    }

    override fun vbatMv(): UInt? {
        val anfrage = Wire.rahmen(Wire.T_VBATT, ByteArray(0))
        val n = synchronized(schreibSperre) {
            verbindung.bulkTransfer(epOut, anfrage, anfrage.size, SCHREIB_TIMEOUT_MS)
        }
        if (n < 0) throw VciException.Io()

        val frist = System.nanoTime() + SPANNUNG_TIMEOUT_MS * 1_000_000L
        while (true) {
            val rahmen = leser.naechster()
            if (rahmen != null) {
                when (rahmen.typ) {
                    Wire.T_VBATT -> return Wire.liesVbatMv(rahmen.nutzlast)
                    Wire.T_ERROR -> throw VciException.Protocol("Gerät meldet Fehler")
                    else -> continue
                }
            }
            val restMs = ((frist - System.nanoTime()) / 1_000_000L).toInt()
            if (restMs <= 0) return null
            val gelesen = verbindung.bulkTransfer(epIn, puffer, puffer.size, restMs)
            if (gelesen < 0) return null
            if (gelesen > 0) leser.schiebe(puffer, gelesen)
        }
    }

    override fun close() {
        verbindung.releaseInterface(schnittstelle)
        verbindung.close()
    }

    companion object {
        private const val SCHREIB_TIMEOUT_MS = 1000
        private const val SPANNUNG_TIMEOUT_MS = 1500

        /**
         * Sucht ein Gerät mit einer herstellerspezifischen Schnittstelle und
         * je einem Bulk-Endpunkt in beide Richtungen.
         *
         * Bewusst ohne feste VID/PID: die Werkstatt steckt den Dongle, den sie
         * hat. Ein Eintrag in `device_filter.xml` wäre erst nötig, wenn Android
         * die App beim Anstecken selbst starten soll.
         */
        fun kandidat(usb: UsbManager): UsbDevice? {
            val geraete = usb.deviceList.values.sortedBy { it.deviceName }
            return geraete.firstOrNull { geeignet(it) != null }
        }

        fun beschreibung(device: UsbDevice): String =
            "VID %04X:PID %04X".format(device.vendorId, device.productId) +
                (device.productName?.let { " · $it" } ?: "")

        fun oeffne(usb: UsbManager, device: UsbDevice): UsbCanTransport? {
            val paare = geeignet(device) ?: return null
            val verbindung = usb.openDevice(device) ?: return null
            if (!verbindung.claimInterface(paare.schnittstelle, true)) {
                verbindung.close()
                return null
            }
            return UsbCanTransport(verbindung, paare.schnittstelle, paare.eingang, paare.ausgang)
        }

        private class Endpunkte(
            val schnittstelle: UsbInterface,
            val eingang: UsbEndpoint,
            val ausgang: UsbEndpoint,
        )

        private fun geeignet(device: UsbDevice): Endpunkte? {
            for (i in 0 until device.interfaceCount) {
                val schnittstelle = device.getInterface(i)
                var eingang: UsbEndpoint? = null
                var ausgang: UsbEndpoint? = null
                for (e in 0 until schnittstelle.endpointCount) {
                    val ep = schnittstelle.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) eingang = ep else ausgang = ep
                }
                if (eingang != null && ausgang != null) {
                    return Endpunkte(schnittstelle, eingang, ausgang)
                }
            }
            return null
        }
    }
}
