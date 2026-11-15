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
    private val usb: UsbManager,
    private val geraet: UsbDevice,
    private val verbindung: UsbDeviceConnection,
    private val schnittstelle: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : CanTransport, AutoCloseable {

    private val leser = RahmenLeser()
    private val schreibSperre = Any()
    private val puffer = ByteArray(4096)
    private val geschlossen = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ein Bulk-Transfer, der nie eine unbehandelte Ausnahme nach oben lässt.
     *
     * `bulkTransfer` liefert -1 sowohl bei Zeitüberschreitung als auch bei
     * gezogenem Kabel — ohne die Geräteprüfung darunter meldet die App in der
     * Werkstatt „Zeitüberschreitung“, obwohl der Dongle weg ist. Und nach dem
     * Abziehen wirft `UsbDeviceConnection` gern `IllegalStateException`; die
     * ginge als „unerwarteter Fehler“ durch die Callback-Grenze und würde den
     * Prozess beenden. Beides wird hier zu dem, was es ist.
     */
    private fun bulk(endpunkt: UsbEndpoint, daten: ByteArray, laenge: Int, timeoutMs: Int): Int {
        val n = try {
            verbindung.bulkTransfer(endpunkt, daten, laenge, timeoutMs)
        } catch (e: RuntimeException) {
            throw VciException.Disconnected()
        }
        if (n < 0 && !nochVerbunden()) throw VciException.Disconnected()
        return n
    }

    /** Steckt das Gerät noch? Nach dem Abziehen fehlt es in der Geräteliste. */
    private fun nochVerbunden(): Boolean =
        runCatching { usb.deviceList.containsKey(geraet.deviceName) }.getOrDefault(false)

    override fun send(frame: CanFrameFfi) {
        if (geschlossen.get()) throw VciException.Disconnected()
        val nutzlast = Wire.canNutzlast(frame.id, frame.data, frame.dlc.toInt())
        val bytes = Wire.rahmen(Wire.T_CAN, nutzlast)
        val n = synchronized(schreibSperre) {
            bulk(epOut, bytes, bytes.size, SCHREIB_TIMEOUT_MS)
        }
        if (n < 0) throw VciException.Io()
    }

    override fun recv(timeoutMs: UInt): CanFrameFfi? {
        if (geschlossen.get()) throw VciException.Disconnected()
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
            val n = bulk(epIn, puffer, puffer.size, restMs)
            if (n < 0) return null
            if (n > 0) leser.schiebe(puffer, n)
        }
    }

    override fun vbatMv(): UInt? {
        if (geschlossen.get()) throw VciException.Disconnected()
        val anfrage = Wire.rahmen(Wire.T_VBATT, ByteArray(0))
        val n = synchronized(schreibSperre) {
            bulk(epOut, anfrage, anfrage.size, SCHREIB_TIMEOUT_MS)
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
            val gelesen = bulk(epIn, puffer, puffer.size, restMs)
            if (gelesen < 0) return null
            if (gelesen > 0) leser.schiebe(puffer, gelesen)
        }
    }

    override fun close() {
        // Zweimal schließen ist harmlos: `UsbDongleVerbindung.close()` und der
        // Bildschirm räumen beide auf.
        if (!geschlossen.compareAndSet(false, true)) return
        runCatching { verbindung.releaseInterface(schnittstelle) }
        runCatching { verbindung.close() }
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
            return UsbCanTransport(
                usb = usb,
                geraet = device,
                verbindung = verbindung,
                schnittstelle = paare.schnittstelle,
                epIn = paare.eingang,
                epOut = paare.ausgang,
            )
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
