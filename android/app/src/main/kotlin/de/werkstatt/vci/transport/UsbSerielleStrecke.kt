package de.werkstatt.vci.transport

/**
 * Serielle Strecke über den USB-Bulk-Endpunkt eines Dongles.
 *
 * Gegenstück zur [BleSerielleStrecke] für den Kabelweg: Befehle raus, Zeilen
 * rein. Der Unterschied liegt im Lesen — USB hat keine Benachrichtigungen,
 * gelesen wird geholt. Deshalb wird hier nicht gewartet und geweckt, sondern
 * in einer Schleife gelesen, bis eine Zeile vollständig ist oder die Frist
 * abläuft.
 *
 * Die beiden Lambdas bilden genau die Naht, die [UsbCanTransport] schon nutzt:
 *
 * - [schreibeBytes] entspricht `bulkTransfer(epOut, …)` und meldet Erfolg.
 * - [leseBytes] entspricht `bulkTransfer(epIn, puffer, puffer.size, timeoutMs)`
 *   und liefert die Zahl gelesener Bytes; kleiner als 0 heißt: Gerät weg.
 *
 * Diese Klasse kennt kein Android und ist damit auf der JVM gegen
 * aufgezeichnete Transfers prüfbar.
 *
 * **Wichtig für den Kabelweg:** das setzt voraus, dass der Dongle seine Bytes
 * roh über die Bulk-Endpunkte schickt, so wie es `UsbCanTransport.kandidat`
 * auswählt. ELM-Klone mit FTDI- oder CP210x-Chip tun das **nicht**: FTDI stellt
 * jedem 64-Byte-Block zwei Statusbytes voran und verlangt die Baudrate per
 * Control-Transfer, CP210x ebenso, und ein CDC-ACM-Gerät braucht Line-Coding
 * über die Klasse `CDC`. Solche Dongles brauchen einen Chip-Treiber; hier
 * kämen die Statusbytes als Datenmüll in der Zeile an. Das ist eine
 * Hardware-Frage, keine Einstellung — siehe die Grenzen in der
 * Dongle-Matrix.
 */
class UsbSerielleStrecke(
    private val schreibeBytes: (ByteArray) -> Boolean,
    private val leseBytes: (ByteArray, Int) -> Int,
) : SerielleStrecke {

    private val puffer = Zeilenpuffer()
    private val lesePuffer = ByteArray(LESE_PUFFER_BYTES)
    private var abbruch = false

    /** Verbindung ist weg — ein laufendes [zeile] soll die Frist nicht abwarten. */
    fun abgebrochen() {
        abbruch = true
    }

    override fun schreibe(text: String): Boolean =
        schreibeBytes((text + WAGENRUECKLAUF).toByteArray(Charsets.US_ASCII))

    override fun zeile(timeoutMs: Long): String? {
        val frist = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val fertig = puffer.naechste()
            if (fertig != null) {
                // Leerzeilen (zweites Byte eines \r\n) verbrauchen, nicht melden.
                if (fertig.isNotEmpty()) return fertig
                continue
            }
            if (abbruch) return null
            val restMs = ((frist - System.nanoTime()) / 1_000_000L).toInt()
            if (restMs <= 0) return null
            val gelesen = leseBytes(lesePuffer, restMs)
            if (gelesen < 0) return null
            if (gelesen > 0) puffer.anhaengen(lesePuffer, gelesen)
        }
    }

    companion object {
        private const val WAGENRUECKLAUF = "\r"

        /** Wie der Puffer in [UsbCanTransport] — ein Transfer, viele Zeilen möglich. */
        private const val LESE_PUFFER_BYTES = 4096
    }
}
