package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.SchluesselAnbieter
import uniffi.vci_ffi.Sicherheit
import uniffi.vci_ffi.SicherheitFfi
import uniffi.vci_ffi.VciException

/**
 * Linktest für `27` (SecurityAccess) über die Sprachgrenze.
 *
 * Geprüft wird, was die Rust-Tests nicht zeigen können: der Anbieter ist in
 * Kotlin geschrieben, wird vom Kern zurückgerufen, und das Ergebnis kommt als
 * eigene Kotlin-Form an. Der Schlüssel entsteht hier aus einer Testregel — im
 * Repository liegt bewusst kein Seed/Key-Algorithmus, auch nicht als Beispiel.
 */
class SicherheitFfiTest {

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

    /** Anbieter mit einer Testregel; merkt sich, was der Kern ihm vorlegt. */
    private class Anbieter(
        private val rechnen: (UInt, ByteArray) -> ByteArray,
    ) : SchluesselAnbieter {
        val gesehen = mutableListOf<Pair<UInt, ByteArray>>()

        override fun schluessel(ebene: UInt, seed: ByteArray): ByteArray {
            gesehen.add(ebene to seed)
            return rechnen(ebene, seed)
        }
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

    @Test
    fun `Zugriff schickt den Schluessel des Anbieters und meldet Entsperrt`() {
        val t = Speicher()
        t.push(antwort(0x67, 0x01, 0xA1, 0xB2))
        t.push(antwort(0x67, 0x02))
        val anbieter = Anbieter { _, seed -> ByteArray(seed.size) { (seed[it].toInt() xor 0xFF).toByte() } }

        val ergebnis = Sicherheit().securityAccess(t, 0x01u, anbieter, 200u)

        assertEquals(SicherheitFfi.ENTSPERRT, ergebnis)
        // Der Anbieter bekam Ebene 1 und den Seed, wie er auf dem Bus lag.
        assertEquals(1, anbieter.gesehen.size)
        assertEquals(0x01u, anbieter.gesehen[0].first)
        assertEquals(listOf(0xA1.toByte(), 0xB2.toByte()), anbieter.gesehen[0].second.toList())

        assertEquals(2, t.gesendet.size)
        assertEquals(0x7E0u, t.gesendet[0].id)
        // Seed-Anfrage `02 27 01`, Schlüssel `04 27 02 5E 4D`.
        assertEquals(listOf(0x02, 0x27, 0x01), t.gesendet[0].data.take(3).map { it.toInt() and 0xFF })
        assertEquals(
            listOf(0x04, 0x27, 0x02, 0x5E, 0x4D),
            t.gesendet[1].data.take(5).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `leerer Seed bedeutet bereits offen und sendet keinen Schluessel`() {
        val t = Speicher()
        t.push(antwort(0x67, 0x01, 0x00, 0x00))
        val anbieter = Anbieter { _, _ -> fail("Anbieter darf nicht gefragt werden"); ByteArray(0) }

        val ergebnis = Sicherheit().securityAccess(t, 0x01u, anbieter, 200u)

        assertEquals(SicherheitFfi.BEREITS_OFFEN, ergebnis)
        assertTrue("nur die Seed-Anfrage ging hinaus", t.gesendet.size == 1)
        assertTrue("Anbieter wurde nicht gebraucht", anbieter.gesehen.isEmpty())
    }

    @Test
    fun `Anbieter ohne Schluessel meldet Protokollfehler statt zu senden`() {
        val t = Speicher()
        t.push(antwort(0x67, 0x01, 0xA1, 0xB2))
        val anbieter = Anbieter { _, _ -> ByteArray(0) }

        try {
            Sicherheit().securityAccess(t, 0x01u, anbieter, 200u)
            fail("erwartet: VciException.Protocol")
        } catch (e: VciException.Protocol) {
            assertTrue("nennt den Grund: ${e.detail}", e.detail.contains("Schlüssel"))
        }
        assertTrue("kein Schlüsselrahmen", t.gesendet.size == 1)
    }

    @Test
    fun `NRC kommt als eigener Fehlerfall an`() {
        val t = Speicher()
        t.push(antwort(0x67, 0x01, 0xA1, 0xB2))
        t.push(antwort(0x7F, 0x27, 0x35))
        val anbieter = Anbieter { _, seed -> seed.copyOf() }

        try {
            Sicherheit().securityAccess(t, 0x01u, anbieter, 200u)
            fail("erwartet: VciException.Nrc")
        } catch (e: VciException.Nrc) {
            assertEquals(0x27u, e.dienst)
            assertEquals(0x35u, e.code)
        }
    }

    @Test
    fun `Ebene ausserhalb eines Bytes wird abgelehnt, bevor etwas rausgeht`() {
        val t = Speicher()
        val anbieter = Anbieter { _, seed -> seed.copyOf() }

        try {
            Sicherheit().securityAccess(t, 0x100u, anbieter, 200u)
            fail("erwartet: VciException.Protocol")
        } catch (e: VciException.Protocol) {
            assertTrue("nennt das Byte: ${e.detail}", e.detail.contains("Byte"))
        }
        assertTrue("nichts gesendet", t.gesendet.isEmpty())
    }
}
