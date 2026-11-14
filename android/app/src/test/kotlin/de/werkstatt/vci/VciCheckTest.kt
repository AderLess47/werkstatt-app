package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Läuft auf der JVM gegen die echte `libvci_ffi.so` (Pfad über `jna.library.path`).
 *
 * Das ersetzt keinen Gerätetest — es beweist nur, dass die Logik des
 * Bildschirms mit dem Kern zusammenarbeitet, wenn die Bibliothek ladbar ist.
 */
class VciCheckTest {

    @Test
    fun `beide Befunde sind gruen`() {
        val befunde = VciCheck.ausfuehren()

        assertEquals("Anzahl der Befunde", 2, befunde.size)
        befunde.forEach { befund ->
            assertTrue("${befund.titel}: ${befund.text}", befund.ok)
        }
    }

    @Test
    fun `Adressierung kommt aus dem Kern`() {
        val kern = VciCheck.ausfuehren().first()
        assertTrue(kern.text, kern.text.contains("0x7E0"))
        assertTrue(kern.text, kern.text.contains("0x7E8"))
    }

    @Test
    fun `ISO-TP meldet die zusammengesetzte Antwort`() {
        val isotp = VciCheck.ausfuehren().last()

        assertEquals("ISO-TP", isotp.titel)
        assertTrue(isotp.text, isotp.text.contains("Antwort zusammengesetzt"))
    }
}
