package de.werkstatt.vci

import de.werkstatt.vci.transport.RahmenLeser
import de.werkstatt.vci.transport.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport

/**
 * Prüft die Dongle-Prüfung gegen ein Gerät, das auf Drahtebene antwortet.
 *
 * Der Simulator hier spricht dieselben Bytes wie der echte VCI (Rahmen mit
 * Sync und CRC) — nur ohne USB. Damit ist die Kette
 * Draht → Rahmenleser → CAN-Rahmen → Rust-Kern → Antwort abgedeckt.
 */
class DonglePruefungTest {

    /** Antwortet auf TesterPresent und meldet 12,4 V. */
    private class DrahtGeraet(private val antwortet: Boolean = true) : CanTransport {

        private val leser = RahmenLeser()

        override fun send(frame: CanFrameFfi) {
            val nutzlast = Wire.canNutzlast(frame.id, frame.data, frame.dlc.toInt())
            val can = Wire.liesCan(nutzlast) ?: return
            if (!antwortet) return
            val istTesterPresent = can.id == 0x7E0u &&
                can.daten[0] == 0x02.toByte() &&
                can.daten[1] == 0x3E.toByte()
            if (!istTesterPresent) return

            val antwort = Wire.rahmen(
                Wire.T_CAN,
                Wire.canNutzlast(0x7E8u, byteArrayOf(0x02, 0x7E, 0x00), 3)
            )
            // In Stücken einspeisen, wie es ein Bulk-Endpunkt täte.
            var ab = 0
            while (ab < antwort.size) {
                val ende = minOf(ab + 4, antwort.size)
                leser.schiebe(antwort.copyOfRange(ab, ende))
                ab = ende
            }
        }

        override fun recv(timeoutMs: UInt): CanFrameFfi? {
            val rahmen = leser.naechster() ?: return null
            if (rahmen.typ != Wire.T_CAN) return null
            val can = Wire.liesCan(rahmen.nutzlast) ?: return null
            return CanFrameFfi(can.id, can.daten, can.dlc.toUByte())
        }

        override fun vbatMv(): UInt? = 12_400u
    }

    /** Gerät ohne Spannungsmessung, das den Bus nicht hört. */
    private class StummesGeraet : CanTransport {
        override fun send(frame: CanFrameFfi) = Unit
        override fun recv(timeoutMs: UInt): CanFrameFfi? = null
        override fun vbatMv(): UInt? = null
    }

    @Test
    fun `antwortendes Geraet besteht beide Pruefungen`() {
        val befunde = DonglePruefung.pruefe(DrahtGeraet())

        assertEquals(2, befunde.size)
        befunde.forEach { assertTrue("${it.titel}: ${it.text}", it.ok) }
        assertTrue(befunde[0].text.contains("12,4 V"))
        assertEquals("Steuergerät", befunde[1].titel)
    }

    @Test
    fun `stummes Geraet meldet fehlende Spannung und fehlende Antwort`() {
        val befunde = DonglePruefung.pruefe(StummesGeraet())

        assertFalse(befunde[0].ok)
        assertTrue(befunde[0].text.contains("keine Spannung"))
        assertFalse(befunde[1].ok)
        assertTrue(befunde[1].text.contains("keine Antwort"))
    }

    @Test
    fun `verfaelschte Antwort wird nicht als Erfolg gewertet`() {
        val befunde = DonglePruefung.pruefe(DrahtGeraet(antwortet = false))

        assertFalse(befunde[1].ok)
    }
}
