package de.werkstatt.vci.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die USB-Strecke gegen aufgezeichnete Transfers.
 *
 * Das Kabel wird hier durch zwei Lambdas ersetzt: eines, das die Ausgabe
 * entgegennimmt, und eines, das die nächsten Bytes liefert — dieselbe Naht, an
 * der [UsbCanTransport] seinen `bulkTransfer` hat.
 *
 * Was hier fehlt, kann nur Hardware zeigen: ob der Dongle seine Bytes roh über
 * die Bulk-Endpunkte schickt (FTDI- und CP210x-Klone tun das nicht), wie die
 * Transfers tatsächlich stückeln und ob die Fristen zu seiner Antwortzeit passen.
 */
class UsbSerielleStreckeTest {

    /**
     * Dongle-Ersatz am Kabel: nimmt Befehle an, legt die Antwort als Bytes ab
     * und gibt sie stückweise wieder heraus.
     */
    private class Kabel(private val stueckGroesse: Int = 6) {

        val befehle = mutableListOf<String>()
        val antworten = mutableMapOf<String, List<String>>()
        var schreibenKlappt = true
        var geraetWeg = false
        private val wartend = ArrayDeque<Byte>()

        fun strecke(): UsbSerielleStrecke = UsbSerielleStrecke(
            schreibeBytes = { bytes ->
                if (!schreibenKlappt) return@UsbSerielleStrecke false
                val befehl = String(bytes, Charsets.US_ASCII).trimEnd('\r')
                befehle += befehl
                val text = antworten[befehl].orEmpty().joinToString("") { it + "\r" }
                text.toByteArray(Charsets.ISO_8859_1).forEach { wartend.addLast(it) }
                true
            },
            leseBytes = { puffer, _ ->
                if (geraetWeg) return@UsbSerielleStrecke -1
                var n = 0
                while (n < minOf(stueckGroesse, puffer.size) && wartend.isNotEmpty()) {
                    puffer[n] = wartend.removeFirst()
                    n++
                }
                n
            },
        )

        fun schiebe(text: String) {
            text.toByteArray(Charsets.ISO_8859_1).forEach { wartend.addLast(it) }
        }
    }

    private fun initialisierung(): MutableMap<String, List<String>> = mutableMapOf(
        "ATZ" to listOf("ELM327 v1.3a"),
        "ATE0" to listOf("OK"),
        "ATL0" to listOf("OK"),
        "ATS0" to listOf("OK"),
        "ATH1" to listOf("OK"),
        "ATSP6" to listOf("OK"),
        "ATSH7E0" to listOf("OK"),
    )

    @Test
    fun `schreibe haengt den Wagenruecklauf an`() {
        val roh = mutableListOf<String>()
        val strecke = UsbSerielleStrecke(
            schreibeBytes = { bytes ->
                roh += String(bytes, Charsets.US_ASCII)
                true
            },
            leseBytes = { _, _ -> 0 },
        )

        assertTrue(strecke.schreibe("ATZ"))
        assertEquals(listOf("ATZ\r"), roh)
    }

    @Test
    fun `Schreibfehler wird gemeldet`() {
        val strecke = UsbSerielleStrecke(
            schreibeBytes = { false },
            leseBytes = { _, _ -> 0 },
        )

        assertFalse(strecke.schreibe("ATZ"))
    }

    @Test
    fun `Zeile wird aus mehreren Transfers zusammengesetzt`() {
        val kabel = Kabel()
        val strecke = kabel.strecke()

        kabel.schiebe("7E8 02 7E")
        // Noch kein Zeilenende: die Frist läuft ab, ohne etwas zu erfinden.
        assertNull(strecke.zeile(30))

        kabel.schiebe(" 00\r")
        assertEquals("7E8 02 7E 00", strecke.zeile(50))
    }

    @Test
    fun `zwei Zeilen in einem Transfer kommen nacheinander`() {
        val kabel = Kabel(stueckGroesse = 4096)
        val strecke = kabel.strecke()

        kabel.schiebe("OK\r7E8 02 7E 00\r")

        assertEquals("OK", strecke.zeile(50))
        assertEquals("7E8 02 7E 00", strecke.zeile(50))
        assertNull(strecke.zeile(20))
    }

    @Test
    fun `Fehler beim Lesen beendet das Warten sofort`() {
        val kabel = Kabel()
        val strecke = kabel.strecke()
        kabel.geraetWeg = true

        val start = System.nanoTime()
        assertNull(strecke.zeile(20_000))
        val dauerMs = (System.nanoTime() - start) / 1_000_000

        // Beweis: bulkTransfer < 0 wird als Abbruch gedeutet, nicht als Frist.
        assertTrue("dauerte $dauerMs ms", dauerMs < 5_000)
    }

    @Test
    fun `Abbruch beendet ein wartendes zeile`() {
        val kabel = Kabel()
        val strecke = kabel.strecke()
        strecke.abgebrochen()

        assertNull(strecke.zeile(20_000))
    }

    @Test
    fun `StnKanal laeuft ueber die USB-Strecke`() {
        val kabel = Kabel()
        kabel.antworten.putAll(initialisierung())
        kabel.antworten["3E00"] = listOf("7E8 02 7E 00")
        val kanal = StnKanal(kabel.strecke())

        kanal.oeffnen()
        val antwort = kanal.anfrage(byteArrayOf(0x3E, 0x00))

        assertArrayEquals(byteArrayOf(0x7E, 0x00), antwort)
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATSH7E0", "3E00"),
            kabel.befehle
        )
    }

    @Test
    fun `lange Antwort ueber viele kleine Transfers`() {
        val kabel = Kabel(stueckGroesse = 4)
        kabel.antworten.putAll(initialisierung())
        kabel.antworten["22F190"] = listOf(
            "7E8 10 14 62 F1 90 57 56 57",
            "7E8 21 5A 5A 5A 31 4B 5A 36",
            "7E8 22 4D 30 30 30 30 30 30",
        )
        val kanal = StnKanal(kabel.strecke())

        kanal.oeffnen()
        val antwort = kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))

        assertEquals(20, antwort.size)
        assertEquals("WVWZZZ1KZ6M000000", antwort.copyOfRange(3, 20).decodeToString())
    }
}
