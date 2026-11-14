package de.werkstatt.vci.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Prüft die Zeilenbildung der BLE-Strecke gegen nachgestellte Benachrichtigungen.
 *
 * Was hier fehlt, kann nur Hardware zeigen: ob der Dongle die Zeilen wirklich so
 * stückelt, ob die Benachrichtigungen ankommen, bevor `zeile` die Frist
 * ablaufen lässt, und ob das Profil (NUS) zum Gerät passt.
 */
class BleSerielleStreckeTest {

    /**
     * Dongle-Ersatz: merkt sich Befehle und schiebt die Antwort in kleinen
     * Stücken in die Strecke — so, wie GATT sie liefert.
     */
    private class Dongle(private val stueckGroesse: Int = 5) {

        lateinit var strecke: BleSerielleStrecke
        val befehle = mutableListOf<String>()
        val antworten = mutableMapOf<String, List<String>>()
        var schreibenKlappt = true

        fun start(): BleSerielleStrecke {
            val neu = BleSerielleStrecke { bytes ->
                if (!schreibenKlappt) return@BleSerielleStrecke false
                val befehl = String(bytes, Charsets.US_ASCII).trimEnd('\r')
                befehle += befehl
                val text = antworten[befehl].orEmpty().joinToString("") { it + "\r" }
                if (text.isNotEmpty()) schiebe(text)
                true
            }
            strecke = neu
            return neu
        }

        fun schiebe(text: String, stueck: Int = stueckGroesse) {
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            var i = 0
            while (i < bytes.size) {
                val n = minOf(stueck, bytes.size - i)
                strecke.empfangen(bytes.copyOfRange(i, i + n))
                i += n
            }
        }
    }

    @Test
    fun `schreibe haengt den Wagenruecklauf an`() {
        val roh = mutableListOf<String>()
        val strecke = BleSerielleStrecke { bytes ->
            roh += String(bytes, Charsets.US_ASCII)
            true
        }

        assertTrue(strecke.schreibe("ATZ"))
        assertEquals(listOf("ATZ\r"), roh)
    }

    @Test
    fun `Schreibfehler wird gemeldet`() {
        val strecke = BleSerielleStrecke { false }

        assertFalse(strecke.schreibe("ATZ"))
    }

    @Test
    fun `Zeile wird aus mehreren Benachrichtigungen zusammengesetzt`() {
        val strecke = BleSerielleStrecke { true }

        strecke.empfangen("7E8 02 7E".toByteArray(Charsets.US_ASCII))
        // Noch kein Zeilenende: die Frist läuft ab, ohne etwas zu erfinden.
        assertNull(strecke.zeile(30))

        strecke.empfangen(" 00\r".toByteArray(Charsets.US_ASCII))
        assertEquals("7E8 02 7E 00", strecke.zeile(50))
    }

    @Test
    fun `zwei Zeilen in einer Benachrichtigung kommen nacheinander`() {
        val strecke = BleSerielleStrecke { true }

        strecke.empfangen("OK\r7E8 02 7E 00\r".toByteArray(Charsets.US_ASCII))

        assertEquals("OK", strecke.zeile(50))
        assertEquals("7E8 02 7E 00", strecke.zeile(50))
        assertNull(strecke.zeile(20))
    }

    @Test
    fun `Zeilenvorschub und Wagenruecklauf gelten beide als Trenner`() {
        val strecke = BleSerielleStrecke { true }

        strecke.empfangen("OK\r\n".toByteArray(Charsets.US_ASCII))
        assertEquals("OK", strecke.zeile(50))

        strecke.empfangen("NO DATA\n".toByteArray(Charsets.US_ASCII))
        assertEquals("NO DATA", strecke.zeile(50))
    }

    @Test
    fun `Benachrichtigung weckt ein wartendes zeile`() {
        val strecke = BleSerielleStrecke { true }

        thread {
            Thread.sleep(80)
            strecke.empfangen("OK\r".toByteArray(Charsets.US_ASCII))
        }

        val start = System.nanoTime()
        val zeile = strecke.zeile(10_000)
        val dauerMs = (System.nanoTime() - start) / 1_000_000

        assertNotNull(zeile)
        assertEquals("OK", zeile)
        // Beweis: die Benachrichtigung weckt, statt die Frist abzuwarten.
        assertTrue("dauerte $dauerMs ms", dauerMs < 6_000)
    }

    @Test
    fun `Abbruch beendet das Warten sofort`() {
        val strecke = BleSerielleStrecke { true }

        thread {
            Thread.sleep(60)
            strecke.abgebrochen("Dongle weg")
        }

        val start = System.nanoTime()
        val zeile = strecke.zeile(20_000)
        val dauerMs = (System.nanoTime() - start) / 1_000_000

        assertNull(zeile)
        assertTrue("dauerte $dauerMs ms", dauerMs < 5_000)
    }

    @Test
    fun `StnKanal laeuft ueber die BLE-Strecke`() {
        val dongle = Dongle()
        dongle.antworten.putAll(
            mapOf(
                "ATZ" to listOf("ELM327 v1.3a"),
                "ATE0" to listOf("OK"),
                "ATL0" to listOf("OK"),
                "ATS0" to listOf("OK"),
                "ATH1" to listOf("OK"),
                "ATSP6" to listOf("OK"),
                "ATSH7E0" to listOf("OK"),
                "3E00" to listOf("7E8 02 7E 00"),
            )
        )
        val kanal = StnKanal(dongle.start())

        kanal.oeffnen()
        val antwort = kanal.anfrage(byteArrayOf(0x3E, 0x00))

        assertArrayEquals(byteArrayOf(0x7E, 0x00), antwort)
        assertEquals("3E00", dongle.befehle.last())
    }

    @Test
    fun `lange Antwort ueber viele kleine Benachrichtigungen`() {
        val dongle = Dongle(stueckGroesse = 3)
        dongle.antworten.putAll(
            mapOf(
                "ATZ" to listOf("ELM327 v1.3a"),
                "ATE0" to listOf("OK"),
                "ATL0" to listOf("OK"),
                "ATS0" to listOf("OK"),
                "ATH1" to listOf("OK"),
                "ATSP6" to listOf("OK"),
                "ATSH7E0" to listOf("OK"),
                "22F190" to listOf(
                    "7E8 10 14 62 F1 90 57 56 57",
                    "7E8 21 5A 5A 5A 31 4B 5A 36",
                    "7E8 22 4D 30 30 30 30 30 30",
                ),
            )
        )
        val kanal = StnKanal(dongle.start())

        kanal.oeffnen()
        val antwort = kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))

        assertEquals(
            "WVWZZZ1KZ6M000000",
            antwort.copyOfRange(3, 20).decodeToString()
        )
    }
}
