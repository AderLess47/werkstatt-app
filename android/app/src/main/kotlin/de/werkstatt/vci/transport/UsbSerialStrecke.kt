package de.werkstatt.vci.transport

import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * Serielle Strecke über einen USB-Seriell-Wandler (`usb-serial-for-android`).
 *
 * Nötig für Dongles, die nicht roh über Bulk-Endpunkte streamen, sondern einen
 * Wandler-Chip haben — der OBDLink EX/SX etwa sitzt hinter einem FTDI-Chip und
 * braucht Baudrate und Leitungsparameter, bevor Daten fließen.
 *
 * Die Arbeit macht [UsbSerielleStrecke] (Zeilenbildung, Fristen, Abbruch); hier
 * steht nur die Übersetzung auf `UsbSerialPort`. `read` und `write` werfen bei
 * gezogenem Kabel eine `IOException` — daraus wird `-1` (Gerät weg) bzw.
 * `false` (Schreiben fehlgeschlagen), wie es die Strecke erwartet.
 */
class UsbSerialStrecke(private val port: UsbSerialPort) : SerielleStrecke {

    private val strecke = UsbSerielleStrecke(
        schreibeBytes = { bytes -> schreibe(bytes) },
        leseBytes = { puffer, timeoutMs -> lese(puffer, timeoutMs) },
    )

    /** Kabel gezogen: ein laufendes [zeile] soll die Frist nicht abwarten. */
    fun abgebrochen() = strecke.abgebrochen()

    override fun schreibe(text: String): Boolean = strecke.schreibe(text)

    override fun zeile(timeoutMs: Long): String? = strecke.zeile(timeoutMs)

    private fun schreibe(bytes: ByteArray): Boolean =
        try {
            port.write(bytes, SCHREIB_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            // IOException bei Abbruch; alles andere ist ebenfalls ein Schreibfehler
            // und darf nicht als „gesendet" durchgehen.
            false
        }

    private fun lese(puffer: ByteArray, timeoutMs: Int): Int =
        try {
            port.read(puffer, timeoutMs)
        } catch (e: Exception) {
            -1
        }

    companion object {
        private const val SCHREIB_TIMEOUT_MS = 1_000
    }
}
