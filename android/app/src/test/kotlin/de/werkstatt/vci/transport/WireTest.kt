package de.werkstatt.vci.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prüft das Drahtformat gegen die Bytes, die der C-Code erzeugt.
 *
 * Die Erwartungswerte kommen nicht aus dieser Datei, sondern aus
 * `j2534/src/transport_usb.c`: ein kleines C-Programm baut dort dieselben
 * Rahmen und gibt sie hexadezimal aus. Weichen die beiden Implementierungen
 * voneinander ab, schlägt der Test hier fehl — nicht erst am Dongle.
 */
class WireTest {

    private fun hex(text: String): ByteArray =
        text.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.alsHex(): String = joinToString(" ") { "%02X".format(it) }

    @Test
    fun `CRC stimmt mit dem C-Code ueberein`() {
        // Bekannter Prüfwert von CRC-16/CCITT-FALSE.
        assertEquals(0x29B1, Wire.crc16("123456789".toByteArray()))
    }

    @Test
    fun `TesterPresent ergibt dieselben Bytes wie in C`() {
        // Der Kern sendet acht Bytes; die drei Nutzbytes werden mit 0xAA aufgefüllt.
        val daten = byteArrayOf(0x02, 0x3E, 0x00) + ByteArray(5) { Wire.PAD }
        val nutzlast = Wire.canNutzlast(0x7E0u, daten, 8)

        assertArrayEquals(
            hex("AA 55 01 0D E0 07 00 00 08 02 3E 00 AA AA AA AA AA C0 F8"),
            Wire.rahmen(Wire.T_CAN, nutzlast)
        )
    }

    @Test
    fun `Antwortrahmen des Steuergeraets stimmt mit C ueberein`() {
        val nutzlast = Wire.canNutzlast(0x7E8u, byteArrayOf(0x02, 0x7E, 0x00), 3)

        assertArrayEquals(
            hex("AA 55 01 0D E8 07 00 00 03 02 7E 00 AA AA AA AA AA CD 2A"),
            Wire.rahmen(Wire.T_CAN, nutzlast)
        )
    }

    @Test
    fun `Spannungsrahmen stimmen mit C ueberein`() {
        assertArrayEquals(
            hex("AA 55 02 00 6D 7B"),
            Wire.rahmen(Wire.T_VBATT, ByteArray(0))
        )
        assertArrayEquals(
            hex("AA 55 02 04 70 30 00 00 86 8B"),
            Wire.rahmen(Wire.T_VBATT, byteArrayOf(0x70, 0x30, 0x00, 0x00))
        )
    }

    @Test
    fun `CAN-Nutzlast ist 13 Byte lang und fuellt mit AA`() {
        val p = Wire.canNutzlast(0x7E0u, byteArrayOf(0x02, 0x3E, 0x00), 3)

        assertEquals(13, p.size)
        assertEquals("E0 07 00 00 03 02 3E 00 AA AA AA AA AA", p.alsHex())
    }

    @Test
    fun `Leser setzt einen in Stuecken ankommenden Rahmen zusammen`() {
        val leser = RahmenLeser()
        val rahmen = hex("AA 55 01 0D E8 07 00 00 03 02 7E 00 AA AA AA AA AA CD 2A")

        // Der Bulk-Endpunkt liefert beliebige Teilstücke.
        for (i in 0 until rahmen.size - 1) {
            leser.schiebe(rahmen.copyOfRange(i, i + 1))
            assertNull("Rahmen kam zu früh", leser.naechster())
        }
        leser.schiebe(rahmen.copyOfRange(rahmen.size - 1, rahmen.size))

        val letzter = leser.naechster()
        assertNotNull(letzter)
        assertEquals(Wire.T_CAN, letzter!!.typ)
        assertEquals(0x7E8u, Wire.liesCan(letzter.nutzlast)!!.id)
        assertEquals(0, leser.crcFehler)
    }

    @Test
    fun `Leser resynchronisiert nach Muell`() {
        val leser = RahmenLeser()
        leser.schiebe(hex("FF 00 13 AA")) // Müll vor dem Rahmen
        leser.schiebe(hex("AA 55 02 00 6D 7B"))

        val rahmen = leser.naechster()
        assertNotNull(rahmen)
        assertEquals(Wire.T_VBATT, rahmen!!.typ)
        assertEquals(12_400u, Wire.liesVbatMv(byteArrayOf(0x70, 0x30, 0x00, 0x00)))
        assertEquals(0, leser.crcFehler)
    }

    @Test
    fun `Leser verwirft einen Rahmen mit falschem CRC und nimmt den naechsten`() {
        val leser = RahmenLeser()
        val kaputt = hex("AA 55 02 04 70 30 00 00 86 00") // letztes CRC-Byte verfälscht
        leser.schiebe(kaputt)
        leser.schiebe(hex("AA 55 02 00 6D 7B"))

        val rahmen = leser.naechster()
        assertNotNull(rahmen)
        assertEquals(Wire.T_VBATT, rahmen!!.typ)
        assertEquals(0, rahmen.nutzlast.size)
        assertEquals(1, leser.crcFehler)
    }

    @Test
    fun `Spannung wird als u32 little endian gelesen`() {
        assertEquals(12_400u, Wire.liesVbatMv(hex("70 30 00 00")))
        assertNull(Wire.liesVbatMv(hex("70 30 00")))
    }
}
