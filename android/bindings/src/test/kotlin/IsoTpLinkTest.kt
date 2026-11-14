package de.werkstatt.vci

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.IsoTp
import uniffi.vci_ffi.VciException

/**
 * Linktest: dieser Test läuft auf der JVM und ruft in die Rust-Bibliothek.
 *
 * Er beweist drei Dinge, die kein Rust-Test zeigen kann: die erzeugten Kotlin-
 * Bindings kompilieren, die Bibliothek lädt über JNA, und der Callback in
 * Gegenrichtung (Kotlin implementiert [CanTransport]) funktioniert.
 *
 * Der Transport ist hier absichtlich von Hand gebaut — auf dem Gerät ist das
 * der BLE- oder USB-Stack, im Test eine Warteschlange.
 */
private class TestTransport : CanTransport {
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

class IsoTpLinkTest {

    @Test
    fun `Anfrage und Antwort laufen durch den Rust-Kern`() {
        val transport = TestTransport()
        // Antwort des Steuergeräts: Single Frame, 02 7E 00, gepolstert.
        transport.push(CanFrameFfi(0x7E8u, byteArrayOf(0x02, 0x7E, 0x00), 8u.toUByte()))

        val iso = IsoTp()
        iso.send(transport, byteArrayOf(0x3E, 0x00))

        assertEquals(1, transport.gesendet.size, "genau eine Anfrage")
        assertEquals(0x7E0u, transport.gesendet[0].id, "Anfrage geht auf 0x7E0")
        assertContentEquals(
            byteArrayOf(0x02, 0x3E, 0x00),
            transport.gesendet[0].data.copyOfRange(0, 3),
            "Single Frame: Länge, Nutzlast"
        )

        val antwort = iso.recv(transport, 500u)
        assertContentEquals(byteArrayOf(0x7E, 0x00), antwort, "Antwort zusammengesetzt")
    }

    @Test
    fun `Multi-Frame wird im Kern segmentiert`() {
        val transport = TestTransport()
        // 20 Byte Anfrage: First Frame + zwei Consecutive Frames.
        val payload = ByteArray(20) { it.toByte() }
        transport.push(CanFrameFfi(0x7E8u, byteArrayOf(0x30, 0x00, 0x00), 8u.toUByte()))

        val iso = IsoTp()
        iso.send(transport, payload)

        assertEquals(3, transport.gesendet.size, "FF + 2 CF")
        // First Frame: TCI 0x10, Länge 0x014 (20 Byte).
        assertEquals(0x10, transport.gesendet[0].data[0].toInt() and 0xF0)
        assertEquals(20, transport.gesendet[0].data[1].toInt() and 0xFF)
        // Consecutive Frames mit fortlaufender Sequenz 1, 2.
        assertEquals(0x21, transport.gesendet[1].data[0].toInt() and 0xFF)
        assertEquals(0x22, transport.gesendet[2].data[0].toInt() and 0xFF)
        // Nutzlast kommt unverändert an: FF trägt Byte 0–5, der erste CF Byte 6.
        assertEquals(6, transport.gesendet[1].data[1].toInt() and 0xFF)
    }

    @Test
    fun `Zeitueberschreitung kommt als VciException an`() {
        val iso = IsoTp()
        val fehler = runCatching { iso.recv(TestTransport(), 10u) }.exceptionOrNull()
        assertTrue(fehler is VciException.Timeout, "erwartet VciException.Timeout, war: $fehler")
    }
}
