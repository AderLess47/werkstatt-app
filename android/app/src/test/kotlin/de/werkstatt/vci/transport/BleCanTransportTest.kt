package de.werkstatt.vci.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.IsoTp
import uniffi.vci_ffi.VciException
import kotlin.concurrent.thread

/**
 * Prüft den BLE-Transport gegen einen Dongle, der auf Byte-Ebene antwortet.
 *
 * Was hier fehlt, ist genau das, was nur echte Hardware zeigen kann: Scan,
 * Verbindungsaufbau und ob der Dongle die Benachrichtigungen so liefert.
 */
class BleCanTransportTest {

    private fun hex(text: String): ByteArray =
        text.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val antwort = "AA 55 01 0D E8 07 00 00 03 02 7E 00 AA AA AA AA AA CD 2A"

    @Test
    fun `sendet dieselben Bytes wie der USB-Weg`() {
        val gesendet = mutableListOf<ByteArray>()
        val transport = BleCanTransport { bytes ->
            gesendet.add(bytes)
            true
        }

        val daten = byteArrayOf(0x02, 0x3E, 0x00) + ByteArray(5) { Wire.PAD }
        transport.send(CanFrameFfi(0x7E0u, daten, 8u.toUByte()))

        assertEquals(1, gesendet.size)
        assertArrayEquals(
            hex("AA 55 01 0D E0 07 00 00 08 02 3E 00 AA AA AA AA AA C0 F8"),
            gesendet.single()
        )
    }

    @Test
    fun `recv setzt eine byteweise ankommende Antwort zusammen`() {
        val transport = BleCanTransport { true }

        // Ohne Daten: nach der Frist nichts, kein Absturz.
        assertNull(transport.recv(50u))

        hex(antwort).forEach { transport.empfangen(byteArrayOf(it)) }

        val rahmen = transport.recv(500u)
        assertNotNull(rahmen)
        assertEquals(0x7E8u, rahmen!!.id)
        assertEquals(3, rahmen.dlc.toInt())
        assertArrayEquals(byteArrayOf(0x02, 0x7E, 0x00), rahmen.data.copyOfRange(0, 3))
    }

    @Test
    fun `Benachrichtigung weckt ein wartendes recv`() {
        val transport = BleCanTransport { true }

        // Antwort kommt erst, wenn recv schon wartet — wie bei echten GATT-Callbacks.
        thread {
            Thread.sleep(80)
            hex(antwort).forEach { transport.empfangen(byteArrayOf(it)) }
        }

        // Großzügige Grenzen: der Test soll beweisen, dass die Benachrichtigung
        // weckt, nicht dass der CI-Runner schnell ist.
        val start = System.nanoTime()
        val rahmen = transport.recv(10_000u)
        val dauerMs = (System.nanoTime() - start) / 1_000_000

        assertNotNull(rahmen)
        assertTrue("dauerte $dauerMs ms", dauerMs < 6_000)
    }

    @Test
    fun `ISO-TP laeuft ueber den BLE-Transport`() {
        val halter = arrayOfNulls<BleCanTransport>(1)
        val transport = BleCanTransport { bytes ->
            val gelesen = RahmenLeser().apply { schiebe(bytes) }.naechster()
            val can = gelesen?.let { Wire.liesCan(it.nutzlast) }
            if (can != null && can.id == 0x7E0u &&
                can.daten[0] == 0x02.toByte() && can.daten[1] == 0x3E.toByte()
            ) {
                val antwortBytes = Wire.rahmen(
                    Wire.T_CAN,
                    Wire.canNutzlast(0x7E8u, byteArrayOf(0x02, 0x7E, 0x00), 3)
                )
                thread { antwortBytes.forEach { halter[0]!!.empfangen(byteArrayOf(it)) } }
            }
            true
        }
        halter[0] = transport

        val iso = IsoTp()
        iso.send(transport, byteArrayOf(0x3E, 0x00))

        assertArrayEquals(byteArrayOf(0x7E, 0x00), iso.recv(transport, 1_000u))
    }

    @Test
    fun `Spannung wird aus der Benachrichtigung gelesen`() {
        val halter = arrayOfNulls<BleCanTransport>(1)
        val transport = BleCanTransport {
            halter[0]!!.empfangen(hex("AA 55 02 04 70 30 00 00 86 8B"))
            true
        }
        halter[0] = transport

        assertEquals(12_400u, transport.vbatMv())
    }

    @Test
    fun `Abbruch weckt ein wartendes recv sofort`() {
        val transport = BleCanTransport { true }
        thread {
            Thread.sleep(60)
            transport.abgebrochen("Dongle weg")
        }

        val start = System.nanoTime()
        try {
            transport.recv(20_000u)
            fail("kein Fehler gemeldet")
        } catch (e: VciException.Disconnected) {
            // erwartet
        }
        // Beweis: der Abbruch weckt, statt die vollen 20 s abzuwarten.
        val dauerMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("dauerte $dauerMs ms", dauerMs < 5_000)
    }

    @Test
    fun `Schreibfehler wird als Disconnected gemeldet`() {
        val transport = BleCanTransport { false }

        try {
            transport.send(CanFrameFfi(0x7E0u, ByteArray(8), 8u.toUByte()))
            fail("kein Fehler gemeldet")
        } catch (e: VciException.Disconnected) {
            // erwartet
        }
    }
}
