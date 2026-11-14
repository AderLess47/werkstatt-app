package de.werkstatt.vci.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die STN-Erkennung gegen aufgezeichnete Antworten.
 *
 * Die Antwortformen stammen aus dem STN-Handbuch: `STI` gibt
 * `STN<Geräte-ID> vX.Y.Z` aus (z. B. „STN1101 v1.1.0"), `ATI` dagegen bewusst
 * einen ELM327-String. Was hier fehlt, kann nur ein Gerät zeigen: ob der
 * angeschlossene Dongle wirklich so antwortet.
 */
class StnKennungTest {

    /** Strecke, die die aufgezeichneten Zeilen der Reihe nach liefert. */
    private class Aufzeichnung(private val antworten: List<String>) : SerielleStrecke {

        val gesendet = mutableListOf<String>()
        var gelesen = 0
        private var index = 0

        override fun schreibe(text: String): Boolean {
            gesendet += text
            return true
        }

        override fun zeile(timeoutMs: Long): String? {
            gelesen++
            return if (index < antworten.size) antworten[index++] else null
        }
    }

    @Test
    fun `erkennt STN-Firmware und fragt STI`() {
        val strecke = Aufzeichnung(listOf("STN2256 v5.9.1"))

        assertEquals("STN2256 v5.9.1", StnKennung.erkenne(strecke))
        assertEquals(listOf("STI"), strecke.gesendet)
    }

    @Test
    fun `erkennt auch aeltere Firmware`() {
        assertEquals("STN1101 v1.1.0", StnKennung.erkenne(Aufzeichnung(listOf("STN1101 v1.1.0"))))
    }

    @Test
    fun `ueberspringt Echo und Leerzeilen`() {
        val strecke = Aufzeichnung(listOf("STI", "", "STN2120 v5.6.0"))
        assertEquals("STN2120 v5.6.0", StnKennung.erkenne(strecke))
    }

    @Test
    fun `lehnt ELM327-Klon ab`() {
        assertNull(StnKennung.erkenne(Aufzeichnung(listOf("ELM327 v1.5"))))
    }

    @Test
    fun `lehnt Fragezeichen ab`() {
        assertNull(StnKennung.erkenne(Aufzeichnung(listOf("?"))))
    }

    @Test
    fun `lehnt blosse Hardware-Kennung ab`() {
        // STDI liefert "OBDLink r1.7" — das ist keine Firmware-Kennung.
        assertNull(StnKennung.erkenne(Aufzeichnung(listOf("OBDLink r1.7"))))
    }

    @Test
    fun `ohne Antwort kein Geraet`() {
        assertNull(StnKennung.erkenne(Aufzeichnung(emptyList())))
    }

    @Test
    fun `Prompt ohne Kennung gilt als Ablehnung`() {
        val strecke = Aufzeichnung(listOf(">"))
        assertNull(StnKennung.erkenne(strecke))
        // Nach dem Prompt wird nicht weiter gelesen.
        assertEquals(1, strecke.gelesen)
    }

    @Test
    fun `istStn akzeptiert nur die Firmwareform`() {
        assertTrue(StnKennung.istStn("STN1100 v1.2.3"))
        assertTrue(StnKennung.istStn("stn2256 v5.9.1"))
        assertFalse(StnKennung.istStn("STN2256"))
        assertFalse(StnKennung.istStn("STN12 v1.0.0"))
        assertFalse(StnKennung.istStn("STN2256 v5.9"))
        assertFalse(StnKennung.istStn("ELM327 v1.5"))
    }

    @Test
    fun `StnKanal kann die erkannte Strecke weiterbenutzen`() {
        val dongle = mapOf(
            "STI" to listOf("STN2256 v5.9.1"),
            "ATZ" to listOf("ELM327 v1.4b"),
            "ATE0" to listOf("OK"),
            "ATL0" to listOf("OK"),
            "ATS0" to listOf("OK"),
            "ATH1" to listOf("OK"),
            "ATSP6" to listOf("OK"),
            "ATSH7E0" to listOf("OK"),
            "3E00" to listOf("7E8 02 7E 00"),
        )
        var aktuell: List<String> = emptyList()
        val strecke = object : SerielleStrecke {
            override fun schreibe(text: String): Boolean {
                aktuell = dongle[text].orEmpty()
                return true
            }
            override fun zeile(timeoutMs: Long): String? =
                if (aktuell.isEmpty()) null else aktuell.first().also { aktuell = aktuell.drop(1) }
        }

        assertEquals("STN2256 v5.9.1", StnKennung.erkenne(strecke))

        val kanal = StnKanal(strecke)
        kanal.oeffnen()
        val antwort = kanal.anfrage(byteArrayOf(0x3E, 0x00))

        assertEquals("7E 00", antwort.joinToString(" ") { "%02X".format(it) })
    }
}
