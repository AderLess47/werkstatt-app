package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.Sfd
import uniffi.vci_ffi.VciException

/**
 * Linktest für den SFD-Ablauf über die Sprachgrenze.
 *
 * Geprüft wird, was die Rust-Tests nicht zeigen können: die erzeugten Kotlin-
 * Bindings kompilieren, die NRC-Ablehnung kommt als eigener Fehlerfall in
 * Kotlin an, und ein langer Token (300 Byte) geht als Multi-Frame hinaus.
 *
 * Der Transport ist hier von Hand gebaut — am Fahrzeug steht an dieser Stelle
 * der USB- oder BLE-Stack (siehe `UsbCanTransport`).
 */
class SfdFfiTest {

    /** Warteschlange als Transport: schickt Rahmen hinein, merkt sich Ausgaben. */
    private class Speicher : CanTransport {
        val gesendet = mutableListOf<CanFrameFfi>()
        private val eingang = ArrayDeque<CanFrameFfi>()

        fun push(frame: CanFrameFfi) {
            eingang.addLast(frame)
        }

        override fun send(frame: CanFrameFfi) {
            gesendet.add(frame)
        }

        override fun recv(timeoutMs: UInt): CanFrameFfi? = eingang.removeFirstOrNull()

        override fun vbatMv(): UInt? = null
    }

    /** Baut einen Antwortrahmen (0x7E8) mit Padding. */
    private fun rahmen(bytes: IntArray): CanFrameFfi {
        val daten = ByteArray(8) { 0xAA.toByte() }
        for (i in bytes.indices) daten[i] = bytes[i].toByte()
        return CanFrameFfi(0x7E8u, daten, 8u.toUByte())
    }

    /** Single Frame um eine UDS-Antwort (höchstens sieben Byte). */
    private fun antwort(vararg uds: Int): CanFrameFfi =
        rahmen(intArrayOf(uds.size) + uds.toList().toIntArray())

    private fun flowControl() = rahmen(intArrayOf(0x30, 0x00, 0x00))

    @Test
    fun `Status lesen liefert Daten und Hex`() {
        val t = Speicher()
        t.push(antwort(0x62, 0x01, 0x74, 0x01))

        val status = Sfd(Audit(), "VIN").status(t, 200u)

        assertEquals("01", status.hex)
        assertEquals(1, status.daten.size)
        // Vec<u8> kommt als List<Byte> an, nicht als UByte.
        assertEquals(0x01.toByte(), status.daten[0])
        // Die Anfrage ging als Single Frame `03 22 01 74` hinaus.
        assertEquals(1, t.gesendet.size)
        assertEquals(0x7E0u, t.gesendet[0].id)
        assertEquals(0x03.toByte(), t.gesendet[0].data[0])
        assertEquals(0x22.toByte(), t.gesendet[0].data[1])
    }

    @Test
    fun `Challenge kommt als Hex-String an`() {
        val t = Speicher()
        t.push(antwort(0x71, 0x01, 0xC0, 0x04, 0xA1, 0xB2))

        val challenge = Sfd(Audit(), "VIN").challenge(t, 0x01u, 200u)

        assertEquals("A1B2", challenge)
        // Rolle steht als fünftes Byte der Anfrage.
        assertEquals(0x05.toByte(), t.gesendet[0].data[0])
        assertEquals(0x31.toByte(), t.gesendet[0].data[1])
        assertEquals(0x01.toByte(), t.gesendet[0].data[5])
    }

    @Test
    fun `NRC kommt als eigener Fehlerfall an`() {
        val t = Speicher()
        t.push(antwort(0x7F, 0x31, 0x35)) // invalidKey

        try {
            Sfd(Audit(), "VIN").entsperren(t, "DEAD", 200u)
            fail("erwartet: VciException.Nrc")
        } catch (e: VciException.Nrc) {
            assertEquals(0x31u, e.dienst)
            assertEquals(0x35u, e.code)
        }
    }

    @Test
    fun `ungueltiger Hex-Text wird abgelehnt`() {
        val t = Speicher()
        try {
            Sfd(Audit(), "VIN").entsperren(t, "A1G2", 200u)
            fail("erwartet: VciException.Sfd")
        } catch (e: VciException.Sfd) {
            assertTrue("nennt die Stelle: ${e.detail}", e.detail.contains("Stelle"))
        }
        assertTrue("nichts gesendet", t.gesendet.isEmpty())
    }

    @Test
    fun `Token mit 300 Byte geht als Multi-Frame hinaus`() {
        val t = Speicher()
        t.push(flowControl())
        t.push(antwort(0x71, 0x01, 0xC0, 0x08))

        Sfd(Audit(), "VIN").entsperrenMitBytes(t, ByteArray(300) { 0x5A }, 200u)

        assertEquals(0x10, t.gesendet[0].data[0].toInt() and 0xF0)
        val angekuendigt = ((t.gesendet[0].data[0].toInt() and 0x0F) shl 8) or
            (t.gesendet[0].data[1].toInt() and 0xFF)
        assertEquals(4 + 300, angekuendigt)
        assertTrue("viele Rahmen für 300 Byte, waren: ${t.gesendet.size}", t.gesendet.size > 40)
    }

    @Test
    fun `Sperren sendet 31 01 C0 09`() {
        val t = Speicher()
        t.push(antwort(0x71, 0x01, 0xC0, 0x09))

        Sfd(Audit(), "VIN").sperren(t, 200u)

        val daten = t.gesendet[0].data
        assertEquals(0x04.toByte(), daten[0])
        assertEquals(0x31.toByte(), daten[1])
        assertEquals(0x01.toByte(), daten[2])
        assertEquals(0xC0.toByte(), daten[3])
        assertEquals(0x09.toByte(), daten[4])
    }

    @Test
    fun `Entsperren und Sperren schreiben in die Audit-Kette`() {
        val audit = Audit()
        val t = Speicher()
        t.push(antwort(0x71, 0x01, 0xC0, 0x08))
        t.push(antwort(0x71, 0x01, 0xC0, 0x09))
        val sfd = Sfd(audit, "WVWZZZ1JZXW000001")

        sfd.entsperren(t, "DEAD", 200u)
        sfd.sperren(t, 200u)

        assertEquals(2u, audit.len())
        val eintraege = audit.eintraege()
        assertEquals("SFD entsperren 31 01 C0 08", eintraege[0].dienst)
        assertEquals("Erfolg", eintraege[0].ergebnis)
        assertEquals("WVWZZZ1JZXW000001", eintraege[0].vin)
        assertEquals("SFD sperren 31 01 C0 09", eintraege[1].dienst)
        // Jeder Eintrag hängt am vorigen.
        assertEquals(eintraege[0].hash, eintraege[1].vorherigerHash)
        audit.pruefen()
        // Der Nachweis belegt den Vorgang, ohne den Schlüssel zu verraten.
        assertTrue("Token steht nicht im Nachweis", !audit.alsJson().contains("DEAD"))
    }
}
