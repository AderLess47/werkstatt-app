package de.werkstatt.vci.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prüft die Zeilenbildung der Textwege — die einzige Stelle, an der BLE und USB
 * dieselbe Logik benutzen.
 *
 * Der Puffer bekommt Bytes in beliebigen Stücken (so wie GATT und `bulkTransfer`
 * sie liefern) und muss daraus vollständige Zeilen machen: nichts erfinden, was
 * noch nicht da ist, nichts verschlucken, was schon da war.
 *
 * Ohne Gerät prüfbar, weil hier kein Transport beteiligt ist — genau deshalb
 * steht die Klasse in einer eigenen Datei. Was nur Hardware zeigt: ob ein echter
 * Dongle mit `\r`, `\r\n` oder ohne Trenner antwortet.
 */
class ZeilenpufferTest {

    private fun puffer(vararg schuebe: String): Zeilenpuffer {
        val p = Zeilenpuffer()
        schuebe.forEach { p.anhaengen(it.toByteArray(Charsets.ISO_8859_1)) }
        return p
    }

    @Test
    fun `unfertige Zeile wird nicht erfunden`() {
        val p = puffer("7E8 02 7E")

        assertNull(p.naechste())
    }

    @Test
    fun `Zeile wird ueber zwei Schuebe fortgesetzt`() {
        val p = puffer("7E8 02 7E", " 00\r")

        assertEquals("7E8 02 7E 00", p.naechste())
        assertNull(p.naechste())
    }

    @Test
    fun `CR LF gilt als ein Zeilenende`() {
        val puffer = puffer("OK\r\n", "7E8 02 7E 00\r\n")

        assertEquals("OK", puffer.naechste())
        assertEquals("7E8 02 7E 00", puffer.naechste())
        // Kein leerer Nachschlag: \r\n ist ein Trenner, nicht zwei.
        assertNull(puffer.naechste())
    }

    @Test
    fun `CR und LF duerfen in verschiedenen Schueben kommen`() {
        val puffer = puffer("OK\r", "\nAB\r")

        assertEquals("OK", puffer.naechste())
        assertEquals("AB", puffer.naechste())
        assertNull(puffer.naechste())
    }

    @Test
    fun `zwei Zeilen in einem Schub kommen nacheinander`() {
        val puffer = puffer("OK\rSEARCHING...\r")

        assertEquals("OK", puffer.naechste())
        assertEquals("SEARCHING...", puffer.naechste())
        assertNull(puffer.naechste())
    }

    @Test
    fun `NUL-Bytes am Zeilenende werden abgeraeumt`() {
        // USB-Wandler hängen je nach Treiber Auffüllbytes an.
        val puffer = puffer("12,4 V\u0000\u0000\r")

        assertEquals("12,4 V", puffer.naechste())
    }

    @Test
    fun `leere Zeile kommt als leerer Text`() {
        val puffer = puffer("\r")

        assertEquals("", puffer.naechste())
        assertNull(puffer.naechste())
    }

    @Test
    fun `aufeinanderfolgende Trenner gelten als ein Umbruch`() {
        // Ein Gerät, das "\r\r" schickt, meint einen Zeilenwechsel, keinen leeren
        // Nachschlag — die Aufrufer überspringen leere Zeilen ohnehin.
        val puffer = puffer("\r\r")

        assertEquals("", puffer.naechste())
        assertNull(puffer.naechste())
    }

    @Test
    fun `anzahl begrenzt den Schub`() {
        val p = Zeilenpuffer()
        val bytes = "OK\rREST".toByteArray(Charsets.ISO_8859_1)

        p.anhaengen(bytes, 3)

        assertEquals("OK", p.naechste())
        // Der abgeschnittene Rest darf nicht im Puffer stehen.
        assertNull(p.naechste())
    }

    @Test
    fun `anzahl null oder negativ aendert nichts`() {
        val p = Zeilenpuffer()

        p.anhaengen("OK\r".toByteArray(), 0)
        p.anhaengen("OK\r".toByteArray(), -5)

        assertNull(p.naechste())
    }

    @Test
    fun `Umlaute und Bytes ueber 7F bleiben erhalten`() {
        // ISO-8859-1 statt UTF-8: ein einzelnes 0x80 darf nicht zu U+FFFD werden.
        val p = Zeilenpuffer()
        p.anhaengen("Gerät \u0080\r".toByteArray(Charsets.ISO_8859_1))

        assertEquals("Gerät \u0080", p.naechste())
    }
}
