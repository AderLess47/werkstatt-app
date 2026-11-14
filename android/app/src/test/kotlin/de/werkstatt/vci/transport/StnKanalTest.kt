package de.werkstatt.vci.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Prüft den STN-Transport gegen **aufgezeichnete** Dongle-Antworten.
 *
 * Die Zeilen unten sind das, was ein OBDLink MX+ (STN2255) über eine serielle
 * Strecke liefert, wenn Echo, Zeilenvorschub und Leerzeichen abgeschaltet sind
 * und Header mit ausgegeben werden. Sie sind von Hand aus der STN-/ELM-Dokumentation
 * und aus Mitschnitten zusammengestellt — **nicht** von einem Gerät in dieser VM.
 *
 * Was hier fehlt, kann nur Hardware zeigen: ob der Dongle nach `ATZ` wirklich so
 * antwortet, wie lange er für einen 4.095-Byte-Transfer braucht, und ob BLE-GATT
 * die Zeilen so stückelt, wie die Strecke es erwartet.
 */
class StnKanalTest {

    /** Strecke, die je Befehl eine feste Zeilenfolge liefert und mitschreibt. */
    private class Aufzeichnung(private val antworten: Map<String, List<String>>) : SerielleStrecke {

        val gesendet = mutableListOf<String>()
        private var puffer = ArrayDeque<String>()

        override fun schreibe(text: String): Boolean {
            gesendet += text
            puffer = ArrayDeque(antworten[text].orEmpty())
            return true
        }

        override fun zeile(timeoutMs: Long): String? =
            if (puffer.isEmpty()) null else puffer.removeFirst()
    }

    /** Strecke, die nie antwortet — für den Zeitüberschreitungsfall. */
    private class Stumm : SerielleStrecke {
        override fun schreibe(text: String) = true
        override fun zeile(timeoutMs: Long): String? = null
    }

    private val initialisierung = mapOf(
        "ATZ" to listOf("ELM327 v1.3a"),
        "ATE0" to listOf("OK"),
        "ATL0" to listOf("OK"),
        "ATS0" to listOf("OK"),
        "ATH1" to listOf("OK"),
        "ATSP6" to listOf("OK"),
        "ATSH7E0" to listOf("OK"),
    )

    private fun kanal(zusatz: Map<String, List<String>> = emptyMap()): Pair<StnKanal, Aufzeichnung> {
        val strecke = Aufzeichnung(initialisierung + zusatz)
        val k = StnKanal(strecke)
        k.oeffnen()
        return k to strecke
    }

    @Test
    fun `oeffnen schickt die Initialisierung in der dokumentierten Reihenfolge`() {
        val strecke = Aufzeichnung(initialisierung)
        StnKanal(strecke).oeffnen()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6"),
            strecke.gesendet
        )
    }

    @Test
    fun `TesterPresent wird als Single Frame gelesen`() {
        val (kanal, strecke) = kanal(mapOf("3E00" to listOf("7E8 02 7E 00")))

        val antwort = kanal.anfrage(byteArrayOf(0x3E, 0x00))

        assertArrayEquals(byteArrayOf(0x7E, 0x00), antwort)
        // Der Header wird vor der ersten Anfrage gesetzt, nicht schon beim Öffnen.
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATSH7E0", "3E00"),
            strecke.gesendet
        )
    }

    @Test
    fun `Header wird nur bei Wechsel neu gesetzt`() {
        val (kanal, strecke) = kanal(mapOf("3E00" to listOf("7E8 02 7E 00")))

        kanal.anfrage(byteArrayOf(0x3E, 0x00))
        kanal.anfrage(byteArrayOf(0x3E, 0x00))

        assertEquals(1, strecke.gesendet.count { it == "ATSH7E0" })
    }

    @Test
    fun `VIN-Antwort aus First und Consecutive Frames wird zusammengesetzt`() {
        val (kanal, _) = kanal(
            mapOf(
                "22F190" to listOf(
                    "7E8 10 14 62 F1 90 57 56 57",
                    "7E8 21 5A 5A 5A 31 4B 5A 36",
                    "7E8 22 4D 30 30 30 30 30 30",
                )
            )
        )

        val antwort = kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))

        val erwartet = byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()) +
            "WVWZZZ1KZ6M000000".toByteArray()
        assertEquals(20, antwort.size)
        assertArrayEquals(erwartet, antwort)
        assertEquals("WVWZZZ1KZ6M000000", antwort.copyOfRange(3, 20).decodeToString())
    }

    @Test
    fun `Antwort eines fremden Steuergeraets wird ignoriert`() {
        val (kanal, _) = kanal(
            mapOf("3E00" to listOf("7E9 02 7E 00", "7E8 02 7E 00"))
        )

        assertArrayEquals(byteArrayOf(0x7E, 0x00), kanal.anfrage(byteArrayOf(0x3E, 0x00)))
    }

    @Test
    fun `negative Antwort bleibt unveraendert`() {
        val (kanal, _) = kanal(mapOf("2201" to listOf("7E8 03 7F 22 31")))

        assertArrayEquals(
            byteArrayOf(0x7F, 0x22, 0x31),
            kanal.anfrage(byteArrayOf(0x22, 0x01))
        )
    }

    @Test(expected = StnFehler.KeineAntwort::class)
    fun `NO DATA ergibt KeineAntwort`() {
        val (kanal, _) = kanal(mapOf("22F190" to listOf("NO DATA")))
        kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
    }

    @Test(expected = StnFehler.BefehlUnbekannt::class)
    fun `Fragezeichen ergibt BefehlUnbekannt`() {
        val (kanal, _) = kanal(mapOf("22F190" to listOf("?")))
        kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
    }

    @Test(expected = StnFehler.BefehlUnbekannt::class)
    fun `unbekannter Initialisierungsbefehl bricht das Oeffnen ab`() {
        val strecke = Aufzeichnung(initialisierung + ("ATE0" to listOf("?")))
        StnKanal(strecke).oeffnen()
    }

    @Test(expected = StnFehler.Zeitueberschreitung::class)
    fun `stummer Dongle ergibt Zeitueberschreitung`() {
        StnKanal(Stumm()).oeffnen()
    }

    @Test
    fun `unvollstaendige Folge wird gemeldet, nicht halb zurueckgegeben`() {
        val (kanal, _) = kanal(
            mapOf(
                "22F190" to listOf(
                    "7E8 10 14 62 F1 90 57 56 57",
                    "7E8 21 5A 5A 5A 31 4B 5A 36",
                )
            )
        )

        try {
            kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
            fail("unvollständige Antwort wurde durchgereicht")
        } catch (e: StnFehler.Uebertragung) {
            assertTrue(e.message!!.contains("unvollst"))
        }
    }

    @Test
    fun `Folgerahmen ausser der Reihe wird gemeldet`() {
        val (kanal, _) = kanal(
            mapOf(
                "22F190" to listOf(
                    "7E8 10 14 62 F1 90 57 56 57",
                    "7E8 22 5A 5A 5A 31 4B 5A 36",
                )
            )
        )

        try {
            kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
            fail("falsche Sequenznummer wurde akzeptiert")
        } catch (e: StnFehler.Uebertragung) {
            assertTrue(e.message!!.contains("Folgerahmen"))
        }
    }

    @Test(expected = StnFehler.Uebertragung::class)
    fun `Anfrage ohne geoeffneten Kanal wird abgelehnt`() {
        StnKanal(Aufzeichnung(initialisierung)).anfrage(byteArrayOf(0x3E, 0x00))
    }

    @Test(expected = StnFehler.Uebertragung::class)
    fun `leere Anfrage wird abgelehnt`() {
        val (kanal, _) = kanal()
        kanal.anfrage(ByteArray(0))
    }

    /**
     * Strecke, die nach der Initialisierung auf jede Anfrage nur Aufschub liefert
     * (mit Prompt dahinter, wie ein Dongle es tut) — für die Obergrenze.
     */
    private class DauerAufschub(private val start: Map<String, List<String>>) : SerielleStrecke {
        private var puffer = ArrayDeque<String>()

        override fun schreibe(text: String): Boolean {
            puffer = ArrayDeque(start[text].orEmpty())
            return true
        }

        override fun zeile(timeoutMs: Long): String? {
            if (puffer.isEmpty()) {
                puffer.add("7E8 03 7F 22 78")
                puffer.add(">")
            }
            return puffer.removeFirst()
        }
    }

    @Test
    fun `Aufschub 0x78 wird abgewartet und die spaetere Antwort geliefert`() {
        // Erste Runde: nur 0x78. Danach — ohne neue Anfrage — die echte Antwort.
        val (kanal, strecke) = kanal(
            mapOf(
                "22F190" to listOf(
                    "7E8 03 7F 22 78",
                    ">",
                    "7E8 06 62 F1 90 57 44 42",
                    ">",
                )
            )
        )

        val antwort = kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))

        assertArrayEquals(
            byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte(), 0x57, 0x44, 0x42),
            antwort
        )
        // Entscheidend: die Anfrage geht genau einmal raus. Ein zweites Senden
        // würde eine zweite Routine starten.
        assertEquals(1, strecke.gesendet.count { it == "22F190" })
    }

    @Test
    fun `nur Aufschub ohne Antwort ergibt Zeitueberschreitung`() {
        val (kanal, _) = kanal(mapOf("22F190" to listOf("7E8 03 7F 22 78", ">")))

        try {
            kanal.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
            fail("0x78 wurde als Antwort durchgereicht")
        } catch (e: StnFehler.Zeitueberschreitung) {
            assertTrue(e.message!!.contains("0x78"))
        }
    }

    @Test
    fun `dauerhafter Aufschub endet nach der Obergrenze`() {
        val strecke = DauerAufschub(initialisierung + ("ATSH7E0" to listOf("OK")))
        val k = StnKanal(strecke)
        k.oeffnen()

        try {
            k.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
            fail("endloser Aufschub wurde durchgelassen")
        } catch (e: StnFehler.Zeitueberschreitung) {
            assertTrue(e.message!!.contains("3-mal 0x78"))
        }
    }

    @Test
    fun `Antwortzeilen ohne Header werden uebergangen`() {
        val (kanal, _) = kanal(
            mapOf("3E00" to listOf("SEARCHING...", "7E8 02 7E 00"))
        )

        assertArrayEquals(byteArrayOf(0x7E, 0x00), kanal.anfrage(byteArrayOf(0x3E, 0x00)))
    }
}
