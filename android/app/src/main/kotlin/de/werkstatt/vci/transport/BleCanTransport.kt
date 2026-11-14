package de.werkstatt.vci.transport

import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.VciException

/**
 * Zweiter Transport: derselbe VCI über BLE statt Kabel.
 *
 * Das Drahtformat ist identisch zum USB-Weg (`Wire`) — der Dongle bekommt
 * dieselben Rahmen, nur über eine GATT-Charakteristik geschrieben statt über
 * einen Bulk-Endpunkt. Gelesen wird über Benachrichtigungen, die Android auf
 * einem eigenen Thread abliefert; `recv` wartet deshalb auf ein Signal.
 *
 * Diese Klasse kennt kein Android: alles, was gesendet wird, geht durch
 * `schreibe`, alles, was ankommt, durch [empfangen]. Damit ist der Transport
 * auf der JVM prüfbar, ohne Bluetooth-Hardware.
 *
 * **Nicht** unterstützt: ELM327-Klone und andere ASCII-Dongles. Die machen
 * ISO-TP selbst und liefern Textzeilen statt CAN-Rahmen — sie passen nicht
 * durch `CanTransport` und sind laut Dongle-Matrix bewusst ausgeschlossen.
 */
class BleCanTransport(private val schreibe: (ByteArray) -> Boolean) : CanTransport {

    private val sperre = Object()
    private val leser = RahmenLeser()
    private var abbruch: String? = null

    /** Von der GATT-Schicht aufgerufen, sobald Nutzdaten ankommen. */
    fun empfangen(bytes: ByteArray, anzahl: Int = bytes.size) {
        synchronized(sperre) {
            leser.schiebe(bytes, anzahl)
            sperre.notifyAll()
        }
    }

    /** Verbindung ist weg — laufendes `recv` soll nicht bis zum Timeout warten. */
    fun abgebrochen(grund: String) {
        synchronized(sperre) {
            abbruch = grund
            sperre.notifyAll()
        }
    }

    fun schliesse() = abgebrochen("Verbindung getrennt")

    override fun send(frame: CanFrameFfi) {
        val bytes = Wire.rahmen(
            Wire.T_CAN,
            Wire.canNutzlast(frame.id, frame.data, frame.dlc.toInt())
        )
        if (!schreibe(bytes)) throw VciException.Disconnected()
    }

    override fun recv(timeoutMs: UInt): CanFrameFfi? {
        val nutzlast = erwarte(Wire.T_CAN, timeoutMs.toLong()) ?: return null
        val can = Wire.liesCan(nutzlast) ?: throw VciException.Protocol("unlesbarer CAN-Rahmen")
        return CanFrameFfi(can.id, can.daten, can.dlc.toUByte())
    }

    override fun vbatMv(): UInt? {
        if (!schreibe(Wire.rahmen(Wire.T_VBATT, ByteArray(0)))) throw VciException.Disconnected()
        val nutzlast = erwarte(Wire.T_VBATT, SPANNUNG_TIMEOUT_MS) ?: return null
        return Wire.liesVbatMv(nutzlast)
    }

    private fun erwarte(typ: Int, timeoutMs: Long): ByteArray? {
        val frist = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(sperre) {
            while (true) {
                abbruch?.let { throw VciException.Disconnected() }
                while (true) {
                    val rahmen = leser.naechster() ?: break
                    if (rahmen.typ == Wire.T_ERROR) {
                        throw VciException.Protocol("Gerät meldet Fehler")
                    }
                    if (rahmen.typ == typ) return rahmen.nutzlast
                }
                val restMs = (frist - System.nanoTime()) / 1_000_000L
                if (restMs <= 0L) return null
                sperre.wait(restMs)
            }
        }
    }

    companion object {
        private const val SPANNUNG_TIMEOUT_MS = 1500L
    }
}
