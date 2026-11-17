package de.werkstatt.vci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vci_ffi.VciException

/**
 * Tests der Logik hinter dem SecurityAccess-Abschnitt.
 *
 * Geprüft wird, was ohne Bildschirm prüfbar ist: die Ebenen-Eingabe, der
 * Anbieter (er rechnet nichts, er merkt sich Ebene und Seed und gibt den
 * eingetragenen Schlüssel zurück) und die Sätze, die auf dem Bildschirm stehen.
 * Die Compose-Darstellung selbst ist damit **nicht** geprüft.
 */
class SicherheitBedienungTest {

    @Test
    fun `Ebene wird hexadezimal gelesen`() {
        assertEquals(0x01, SicherheitEbenen.pruefen("01").wert)
        assertEquals(0x05, SicherheitEbenen.pruefen("0x5").wert)
        assertEquals(0x11, SicherheitEbenen.pruefen(" 11 ").wert)
    }

    @Test
    fun `gerade Zahl ist eine Schluessel-Unterfunktion, keine Ebene`() {
        val geprueft = SicherheitEbenen.pruefen("02")
        assertNull(geprueft.wert)
        assertTrue("${geprueft.fehler}", geprueft.fehler.orEmpty().contains("ungerade"))
    }

    @Test
    fun `Ebene ausserhalb eines Bytes wird abgelehnt`() {
        assertTrue(SicherheitEbenen.pruefen("100").fehler.orEmpty().contains("Byte"))
        assertTrue(SicherheitEbenen.pruefen("").fehler.orEmpty().contains("leer"))
        assertTrue(SicherheitEbenen.pruefen("G1").fehler.orEmpty().contains("Hexziffer"))
    }

    @Test
    fun `Anbieter merkt sich Ebene und Seed und gibt den Schluessel zurueck`() {
        val anbieter = SicherheitsAnbieter(byteArrayOf(0x5E, 0x4D))
        assertNull("vor dem Aufruf ist nichts gesehen", anbieter.seed)

        val zurueck = anbieter.schluessel(0x01u, byteArrayOf(0xA1.toByte(), 0xB2.toByte()))

        assertArrayEquals(byteArrayOf(0x5E, 0x4D), zurueck)
        assertEquals(0x01, anbieter.ebene)
        assertArrayEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte()), anbieter.seed)
    }

    @Test
    fun `Seed und Ergebnis werden benannt statt nur als Hex gezeigt`() {
        assertEquals("noch kein Seed gelesen", SicherheitAnzeige.seedText(null))
        assertTrue(SicherheitAnzeige.seedText(ByteArray(0)).contains("keinen Seed"))
        assertTrue(SicherheitAnzeige.seedText(byteArrayOf(0, 0)).contains("nur Nullbytes"))
        assertEquals(
            "2 Byte: A1 B2",
            SicherheitAnzeige.seedText(byteArrayOf(0xA1.toByte(), 0xB2.toByte())),
        )
        assertTrue(SicherheitAnzeige.bereitsOffen(0x01).contains("bereits offen"))
        assertTrue(SicherheitAnzeige.entsperrt(0x01, byteArrayOf(1, 2, 3)).contains("entsperrt"))
        assertEquals("0x03", SicherheitAnzeige.ebeneText(0x03))
        assertArrayEquals(byteArrayOf(0x5E, 0x4D), SicherheitAnzeige.bytes("5E4D"))
    }

    @Test
    fun `leerer Schluessel wird als fehlende Eingabe gemeldet, nicht als Busfehler`() {
        val meldung = SicherheitAnzeige.meldung(
            0x01,
            VciException.Protocol(
                "kein Schlüssel für Ebene 0x01: der SecurityProvider hat keinen geliefert",
            ),
        )

        assertTrue(meldung.text, meldung.text.contains("kein Schlüssel vorhanden"))
        assertTrue(meldung.hinweis.orEmpty(), meldung.hinweis.orEmpty().contains("eintragen"))
        assertEquals(SfdFehler.Art.PROTOKOLL, meldung.art)
    }
}
