package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Prüft die Sekundenangabe in der Wartezeile.
 *
 * Der Text steht in der Werkstatt-Anleitung wörtlich so („Frist 1,5 s je
 * Rahmen“), deshalb ist die Formatierung kein Detail: mit englischer Schreibweise
 * („1.5 s“) würde sie nicht mehr zu dem passen, was die Anleitung zitiert.
 */
class FristTest {

    @Test
    fun `Frist erscheint deutsch mit Komma`() {
        assertEquals("1,5 s", fristText(1_500))
        assertEquals("4,5 s", fristText(4_500))
    }

    @Test
    fun `ganze Sekunden behalten eine Nachkommastelle`() {
        // Damit alle Zeilen gleich breit wirken und dieselbe Form haben.
        assertEquals("3,0 s", fristText(3_000))
        assertEquals("2,0 s", fristText(2_000))
        assertEquals("5,0 s", fristText(5_000))
    }

    @Test
    fun `Millisekunden unter einer Sekunde`() {
        assertEquals("0,8 s", fristText(800))
    }

    @Test
    fun `null bleibt eine Sekundenangabe`() {
        assertEquals("0,0 s", fristText(0))
    }

    @Test
    fun `keine englische Schreibweise`() {
        // Ein Punkt wäre der Fehler — dann stünde „1.5 s“ in der Werkstatt-Anleitung.
        assertFalse(fristText(1_500).contains('.'))
    }
}
