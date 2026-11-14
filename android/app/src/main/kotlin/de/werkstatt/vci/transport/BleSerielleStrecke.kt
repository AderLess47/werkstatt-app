package de.werkstatt.vci.transport

/**
 * Serielle Strecke über den BLE-GATT-UART eines Dongles.
 *
 * Der Dongle hängt an einer GATT-Charakteristik (Standardprofil NUS, siehe
 * [BleGattVerbindung]): Befehle werden als Bytes geschrieben, Antworten kommen
 * als Benachrichtigungen. Was hier dazwischen fehlt, ist die Zeilenbildung —
 * und genau die ist der Grund für diese Klasse:
 *
 * - Eine Benachrichtigung endet **nicht** an der Zeilengrenze. Je nach MTU
 *   kommen 20 Byte, ein `\r` fällt mitten in die nächste Benachrichtigung.
 * - Umgekehrt kann eine Benachrichtigung zwei Antworten enthalten
 *   (`OK\rNO DATA\r`).
 * - Die Benachrichtigung trifft auf einem Binder-Thread ein, `zeile` blockiert
 *   auf dem Aufrufer-Thread. Beide müssen sich unter einer Sperre treffen.
 *
 * Deshalb wird hier gepuffert und erst an `\r` oder `\n` eine Zeile
 * herausgegeben; ein unfertiger Rest bleibt liegen und wird beim nächsten
 * Eintreffen fortgesetzt. `zeile` liefert `null` bei Fristablauf und bei
 * Verbindungsabbruch — [StnKanal] macht daraus eine Zeitüberschreitung.
 *
 * Diese Klasse kennt kein Android: alles Ausgehende geht durch [schreibeBytes],
 * alles Eingehende durch [empfangen]. Damit ist sie auf der JVM gegen
 * aufgezeichnete Benachrichtigungen prüfbar; Scan, Verbindungsaufbau und das
 * tatsächliche Fragmentieren bleiben Sache von [BleGattVerbindung] und der
 * Hardware.
 */
class BleSerielleStrecke(private val schreibeBytes: (ByteArray) -> Boolean) : SerielleStrecke {

    private val sperre = Object()
    private val puffer = StringBuilder()
    private var abbruch: String? = null

    /**
     * Von der GATT-Schicht aufgerufen, sobald Nutzdaten ankommen.
     *
     * [anzahl] erlaubt es, nur einen Teil eines Puffers zu übernehmen, wie es
     * der `BluetoothGattCallback` je nach Android-Version liefert.
     */
    fun empfangen(bytes: ByteArray, anzahl: Int = bytes.size) {
        if (anzahl <= 0) return
        synchronized(sperre) {
            // ISO-8859-1, damit kein Byte unterwegs verloren geht: die Antwort
            // ist ASCII, aber ein 0x80 im Fehlerfall soll nicht zu U+FFFD werden.
            puffer.append(String(bytes, 0, anzahl, Charsets.ISO_8859_1))
            sperre.notifyAll()
        }
    }

    /** Verbindung ist weg — ein wartendes [zeile] soll nicht bis zur Frist warten. */
    fun abgebrochen(grund: String) {
        synchronized(sperre) {
            abbruch = grund
            sperre.notifyAll()
        }
    }

    fun schliesse() = abgebrochen("Strecke geschlossen")

    /**
     * Ein Befehl endet bei ELM-kompatiblen Geräten mit Wagenrücklauf; `ATL0`
     * schaltet nur den zusätzlichen Zeilenvorschub ab.
     */
    override fun schreibe(text: String): Boolean {
        val bytes = (text + WAGENRUECKLAUF).toByteArray(Charsets.US_ASCII)
        return schreibeBytes(bytes)
    }

    override fun zeile(timeoutMs: Long): String? {
        val frist = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(sperre) {
            while (true) {
                val fertig = holeZeile()
                if (fertig != null) {
                    // Leerzeilen (etwa das zweite Byte eines \r\n) verbrauchen,
                    // aber nicht als Antwort melden.
                    if (fertig.isNotEmpty()) return fertig
                    continue
                }
                if (abbruch != null) return null
                val restMs = (frist - System.nanoTime()) / 1_000_000L
                if (restMs <= 0L) return null
                sperre.wait(restMs)
            }
        }
    }

    /** Nächste vollständige Zeile aus dem Puffer, oder `null`, wenn noch keine da ist. */
    private fun holeZeile(): String? {
        val ende = puffer.indexOfFirst { it == '\r' || it == '\n' }
        if (ende < 0) return null
        val zeile = puffer.substring(0, ende).trim('\u0000')
        var weiter = ende
        while (weiter < puffer.length && (puffer[weiter] == '\r' || puffer[weiter] == '\n')) weiter++
        puffer.delete(0, weiter)
        return zeile
    }

    companion object {
        private const val WAGENRUECKLAUF = "\r"
    }
}
