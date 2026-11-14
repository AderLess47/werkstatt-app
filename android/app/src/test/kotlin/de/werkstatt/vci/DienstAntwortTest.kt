package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft die Lesefassung der Klasse-1-Antworten (Hex, Text, Ablehnungen). */
class DienstAntwortTest {

    private val vin = byteArrayOf(
        0x62, 0xF1.toByte(), 0x90.toByte(),
        0x57, 0x44, 0x42, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
    )

    @Test
    fun `hex schreibt Bytes mit Leerzeichen`() {
        assertEquals("62 F1 90", DienstAntwort.hex(byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte())))
        assertEquals("", DienstAntwort.hex(ByteArray(0)))
    }

    @Test
    fun `ascii behaelt nur druckbare Zeichen`() {
        // Das 0x62 des Dienstes ist selbst druckbar ("b"), 0xF1/0x90 fallen weg.
        assertEquals("bWDB123456789", DienstAntwort.ascii(vin))
        assertEquals("", DienstAntwort.ascii(byteArrayOf(0x00, 0x01, 0x80.toByte())))
    }

    @Test
    fun `Ablehnung wird an 7F erkannt`() {
        assertTrue(DienstAntwort.istAblehnung(byteArrayOf(0x7F, 0x22, 0x31)))
        assertFalse(DienstAntwort.istAblehnung(vin))
        assertFalse(DienstAntwort.istAblehnung(ByteArray(0)))
    }

    @Test
    fun `0x78 wird als Aufschub erkannt, nicht als Ergebnis`() {
        val aufschub = byteArrayOf(0x7F, 0x22, 0x78)
        assertTrue(DienstAntwort.istAntwortNochOffen(aufschub))
        // Es *ist* formal eine Ablehnung — deshalb muss die Prüfung vor der
        // Anzeige kommen, sonst steht „abgelehnt“ auf dem Bildschirm.
        assertTrue(DienstAntwort.istAblehnung(aufschub))
        assertFalse(DienstAntwort.istAntwortNochOffen(byteArrayOf(0x7F, 0x22, 0x31)))
        assertFalse(DienstAntwort.istAntwortNochOffen(byteArrayOf(0x7F, 0x22)))
        assertFalse(DienstAntwort.istAntwortNochOffen(vin))
    }

    @Test
    fun `NRC wird im Klartext erklaert`() {
        assertEquals("Wertebereich nicht unterstützt — falsche Kennung?", DienstAntwort.nrcText(0x31))
        assertEquals("Bedingungen nicht erfüllt — Zündung an?", DienstAntwort.nrcText(0x22))
        assertEquals("unbekannter Code", DienstAntwort.nrcText(0x99))
    }

    @Test
    fun `positive Antwort zeigt Groesse Hex und Text`() {
        val text = DienstAntwort.beschreibe(vin)

        assertTrue(text.startsWith("15 Byte · 62 F1 90 57 44"))
        assertTrue(text.endsWith("· Text: bWDB123456789"))
    }

    @Test
    fun `Ablehnung steht im Klartext vor der Bytefolge`() {
        assertEquals(
            "abgelehnt (Dienst 22, NRC 31: Wertebereich nicht unterstützt — falsche Kennung?)",
            DienstAntwort.beschreibe(byteArrayOf(0x7F, 0x22, 0x31))
        )
    }

    @Test
    fun `leere und kurze Ablehnungen stuerzen nicht ab`() {
        assertEquals("leere Antwort", DienstAntwort.beschreibe(ByteArray(0)))
        assertEquals("abgelehnt (Dienst 22, ohne Grund)", DienstAntwort.beschreibe(byteArrayOf(0x7F, 0x22)))
    }
}
