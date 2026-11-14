package de.werkstatt.vci

import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.IsoTp

/**
 * Ergebnis einer Einzelprüfung, fertig für die Anzeige.
 */
data class Befund(
    val titel: String,
    val text: String,
    val ok: Boolean
)

/**
 * Prüft, ob der Kern auf diesem Gerät überhaupt arbeitsfähig ist.
 *
 * Das ist bewusst noch **kein** Fahrzeugtest: geprüft wird, ob die native
 * Bibliothek lädt und ob der ISO-TP-Kern eine Anfrage korrekt segmentiert und
 * eine Antwort zusammensetzt. Der Transport ist hier ein Simulator im
 * Arbeitsspeicher — auf dem Gerät wird daraus der BLE- oder USB-Stack.
 */
object VciCheck {

    fun ausfuehren(): List<Befund> = listOf(pruefeBibliothek(), pruefeIsotp())

    private fun pruefeBibliothek(): Befund =
        try {
            val iso = IsoTp()
            Befund(
                titel = "Kern geladen",
                text = "libvci_ffi bereit · Adressierung 0x%02X → 0x%02X"
                    .format(iso.reqId().toLong(), iso.rspId().toLong()),
                ok = true
            )
        } catch (e: Throwable) {
            // UnsatisfiedLinkError ist ein Error, kein Exception — deshalb Throwable.
            Befund("Kern nicht ladbar", e.message ?: e.toString(), ok = false)
        }

    private fun pruefeIsotp(): Befund =
        try {
            val iso = IsoTp()
            val transport = SimulatorTransport()
            iso.send(transport, byteArrayOf(0x3E, 0x00))
            val antwort = iso.recv(transport, 500u)
            if (antwort.contentEquals(byteArrayOf(0x7E, 0x00))) {
                Befund("ISO-TP", "Anfrage gesendet, Antwort zusammengesetzt", ok = true)
            } else {
                Befund("ISO-TP", "unerwartete Antwort: ${antwort.toHex()}", ok = false)
            }
        } catch (e: Throwable) {
            Befund("ISO-TP", e.toString(), ok = false)
        }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}

/**
 * Simuliertes Steuergerät im Arbeitsspeicher: antwortet auf TesterPresent.
 * Ersetzt noch keinen Dongle — hält die Oberfläche aber ehrlich, weil sie
 * ohne echten Transport auch keinen Erfolg behaupten kann.
 */
private class SimulatorTransport : CanTransport {
    private val eingang = ArrayDeque<CanFrameFfi>()

    override fun send(frame: CanFrameFfi) {
        val daten = frame.data
        val istSingleFrame = (daten[0].toInt() and 0xF0) == 0x00
        val laenge = daten[0].toInt() and 0x0F
        if (istSingleFrame && laenge >= 2 && daten[1] == 0x3E.toByte()) {
            eingang.addLast(
                CanFrameFfi(
                    id = 0x7E8u,
                    data = byteArrayOf(0x02, 0x7E, 0x00),
                    dlc = 8u.toUByte()
                )
            )
        }
    }

    override fun recv(timeoutMs: UInt): CanFrameFfi? = eingang.removeFirstOrNull()

    override fun vbatMv(): UInt? = null
}
