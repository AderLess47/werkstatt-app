package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vci_ffi.VciException

/**
 * Prüft die Logik hinter dem SFD-Bildschirm, ohne Android und ohne Dongle.
 *
 * Was hier festgehalten wird, ist die Arbeitsteilung: der Kern baut die
 * Nachrichten, diese Schicht entscheidet, **was der Techniker liest**. Deshalb
 * stehen die Fälle hier, die in der Halle zu Verwechslungen führen — falscher
 * Token, Aufschub, abgezogenes Kabel — und die Deutung der Statusbytes, deren
 * Annahme ohne Fahrzeug nur durch einen Test festgehalten werden kann.
 */
class SfdBedienungTest {

    // --- Token ------------------------------------------------------------

    @Test
    fun `Token mit Trennern wird normalisiert`() {
        val p = SfdToken.pruefen("5A:5a- 5A\t5A")
        assertTrue(p.ok)
        assertEquals("5A5A5A5A", p.normalisiert)
        assertEquals(4, p.bytes)
        assertNull(p.fehler)
    }

    @Test
    fun `ungerade Ziffernzahl wird abgelehnt`() {
        val p = SfdToken.pruefen("5A5")
        assertFalse(p.ok)
        assertTrue("nennt die Länge: ${p.fehler}", p.fehler!!.contains("ungerade"))
    }

    @Test
    fun `Nicht-Hex-Zeichen nennt die Stelle`() {
        val p = SfdToken.pruefen("5AG2")
        assertFalse(p.ok)
        // Stelle 3 (1-basiert), weil A gültig ist und G nicht.
        assertTrue("nennt die Stelle: ${p.fehler}", p.fehler!!.contains("Stelle 3"))
    }

    @Test
    fun `leere Eingabe ist kein Fehler sondern leer`() {
        val p = SfdToken.pruefen("   \n")
        assertFalse(p.ok)
        assertTrue(p.leer)
        assertNull(p.fehler)
    }

    @Test
    fun `Token an der Grenze passt, ein Byte mehr nicht`() {
        val grenze = SfdToken.pruefen("5A".repeat(SfdToken.MAX_BYTES))
        assertTrue(grenze.ok)
        assertEquals(SfdToken.MAX_BYTES, grenze.bytes)

        val zuLang = SfdToken.pruefen("5A".repeat(SfdToken.MAX_BYTES + 1))
        assertFalse(zuLang.ok)
        assertTrue("nennt die Grenze: ${zuLang.fehler}", zuLang.fehler!!.contains("4091"))
    }

    // --- Status -----------------------------------------------------------

    @Test
    fun `leere Statusantwort bleibt unbekannt`() {
        val d = SfdStatusDeutung.deute(ByteArray(0))
        assertNull(d.gesperrt)
        assertEquals("", d.roh)
        assertTrue(d.text.contains("keine Statusbytes"))
    }

    @Test
    fun `Byte 0 mit 0x00 heisst gesperrt`() {
        val d = SfdStatusDeutung.deute(byteArrayOf(0x00))
        assertEquals(true, d.gesperrt)
        assertEquals("gesperrt", d.text)
        assertEquals("00", d.roh)
        assertNull(d.restMinuten)
    }

    @Test
    fun `Byte 0 mit 0x01 nennt Rolle und Restlaufzeit`() {
        // 0x01 entsperrt · 0x02 Rolle 2 · 0x001E = 30 Minuten
        val d = SfdStatusDeutung.deute(byteArrayOf(0x01, 0x02, 0x00, 0x1E))
        assertEquals(false, d.gesperrt)
        assertEquals(0x02, d.rolle)
        assertEquals("Rolle 2", d.rolleText)
        assertEquals(30, d.restMinuten)
        assertTrue("nennt die Rolle: ${d.text}", d.text.contains("Rolle 0x02 (Rolle 2)"))
        assertTrue("nennt die Minuten: ${d.text}", d.text.contains("30 min"))
        assertEquals("0102001E", d.roh)
    }

    @Test
    fun `entsperrt ohne Rollenbyte sagt das dazu`() {
        val d = SfdStatusDeutung.deute(byteArrayOf(0x01, 0x00))
        assertEquals(false, d.gesperrt)
        assertNull(d.rolle)
        assertTrue(d.text.contains("keine aktive Rolle"))
    }

    @Test
    fun `unbekanntes Zustandsbyte wird nicht geraten`() {
        val d = SfdStatusDeutung.deute(byteArrayOf(0x42, 0x01, 0x00, 0x05))
        assertNull(d.gesperrt)
        assertNull(d.rolle)
        assertEquals("42010005", d.roh)
        assertTrue("nennt das Byte: ${d.text}", d.text.contains("0x42"))
        assertTrue(d.text.contains("unbekannt"))
    }

    @Test
    fun `Rollennamen decken die drei angebotenen Rollen ab`() {
        assertEquals("Extended", SfdStatusDeutung.rolleText(0x01))
        assertEquals("Rolle 2", SfdStatusDeutung.rolleText(0x02))
        assertEquals("Entwicklung", SfdStatusDeutung.rolleText(0x03))
        assertEquals("unbekannte Rolle", SfdStatusDeutung.rolleText(0x55))
        assertEquals(0x02, SfdRolle.ausCode(0x02)?.code)
        assertNull(SfdRolle.ausCode(0x7A))
    }

    // --- Fehlerabbildung --------------------------------------------------

    @Test
    fun `falscher Token kommt als UDS-Ablehnung mit Hinweis`() {
        val m = SfdFehler.aus(VciException.Nrc(0x31u, 0x35u))
        assertEquals(SfdFehler.Art.NRC, m.art)
        assertTrue("nennt den NRC: ${m.text}", m.text.contains("0x35"))
        assertTrue("nennt den Klartext: ${m.text}", m.text.contains("falscher Schlüssel"))
        assertTrue("gibt einen Hinweis: ${m.hinweis}", m.hinweis!!.contains("Token falsch"))
    }

    @Test
    fun `Aufschub 0x78 ist kein Fehler im engeren Sinn`() {
        val m = SfdFehler.aus(VciException.Nrc(0x31u, 0x78u))
        assertEquals(SfdFehler.Art.AUFSCHUB, m.art)
        assertTrue("nennt das Warten: ${m.hinweis}", m.hinweis!!.contains("rechnet weiter"))
    }

    @Test
    fun `Formatfehler aus dem Kern bleibt Formatfehler`() {
        val m = SfdFehler.aus(VciException.Sfd("Token ist leer"))
        assertEquals(SfdFehler.Art.FORMAT, m.art)
        assertTrue(m.text.contains("Token ist leer"))
        assertFalse(m.text.contains("getrennt"))
    }

    @Test
    fun `abgezogenes Kabel wird als USB gemeldet, nicht als Zeitueberschreitung`() {
        val m = SfdFehler.aus(VciException.Disconnected())
        assertEquals(SfdFehler.Art.USB, m.art)
        assertTrue(m.text.contains("getrennt"))
        assertTrue("sagt, was zu tun ist: ${m.hinweis}", m.hinweis!!.contains("Kabel"))
    }

    @Test
    fun `Zeitueberschreitung nennt die Adressierung als moegliche Ursache`() {
        val m = SfdFehler.aus(VciException.Timeout())
        assertEquals(SfdFehler.Art.ZEITUEBERSCHREITUNG, m.art)
        assertTrue("nennt die Adressierung: ${m.hinweis}", m.hinweis!!.contains("0x7E0"))
    }

    @Test
    fun `Sitzungs-NRC nennt die Sitzung`() {
        val m = SfdFehler.aus(VciException.Nrc(0x31u, 0x7Fu))
        assertTrue(m.hinweis!!.contains("Sitzung"))
    }

    @Test
    fun `ohne offenen Transport sagt die Meldung was zu tun ist`() {
        val m = SfdFehler.keinTransport()
        assertEquals(SfdFehler.Art.USB, m.art)
        assertTrue(m.hinweis!!.contains("USB-Dongle öffnen"))
    }

    // --- Doppelklick-Schutz -----------------------------------------------

    @Test
    fun `zweiter Aufruf waehrend eines laufenden wird abgewiesen`() {
        val sperre = SfdSperre()
        assertTrue(sperre.belegen("Status lesen"))
        assertEquals("Status lesen", sperre.aktiv)
        assertFalse("zweiter Druck darf nichts starten", sperre.belegen("Token senden"))
        assertEquals("der erste Text bleibt stehen", "Status lesen", sperre.aktiv)

        sperre.freigeben()
        assertNull(sperre.aktiv)
        assertTrue("nach dem Freigeben wieder frei", sperre.belegen("Token senden"))
    }

    @Test
    fun `mehrfaches Freigeben ist harmlos`() {
        val sperre = SfdSperre()
        sperre.freigeben()
        sperre.freigeben()
        assertNull(sperre.aktiv)
        assertTrue(sperre.belegen("Challenge anfordern"))
    }

    // --- Rohantwort -------------------------------------------------------

    @Test
    fun `Rohantwort wird unveraendert durchgereicht`() {
        // So, wie sie aus dem FFI kommt: ganze Antwort inklusive Kopf.
        val d = SfdStatusDeutung.deute(
            daten = byteArrayOf(0x01, 0x02, 0x00, 0x1E),
            rohAntwort = "6201740102001E",
        )
        assertEquals("6201740102001E", d.rohAntwort)
        assertEquals("0102001E", d.roh)
        assertEquals(7, d.rohAntwort.length / 2)
    }

    @Test
    fun `abweichendes Layout scheitert nicht und behaelt die Rohbytes`() {
        // SFD2 könnte mehr Bytes liefern: gedeutet wird der bekannte Teil,
        // der Rest bleibt im Rohwert sichtbar.
        val daten = byteArrayOf(0x01, 0x01, 0x00, 0x0A, 0x55, 0x66, 0x77)
        val d = SfdStatusDeutung.deute(daten, "6201740101000A556677")
        assertEquals(false, d.gesperrt)
        assertEquals(10, d.restMinuten)
        assertEquals("0101000A556677", d.roh)
        assertEquals(10, d.rohAntwort.length / 2)
    }

    // --- Ziel-Steuergerät -------------------------------------------------

    @Test
    fun `Voreinstellungen haben die ueblichen Kennungen`() {
        assertEquals(0x710, SfdZiele.GATEWAY.anfrageId)
        assertEquals(0x718, SfdZiele.GATEWAY.antwortId)
        assertEquals(11, SfdZiele.GATEWAY.bits)

        assertEquals(0x7E0, SfdZiele.MOTOR.anfrageId)
        assertEquals(0x7E8, SfdZiele.MOTOR.antwortId)
        assertEquals(11, SfdZiele.MOTOR.bits)

        // Voreinstellung ist die Motorsteuergerät-Adressierung.
        assertEquals(SfdZiele.MOTOR, SfdZiele.STANDARD)
        assertEquals(2, SfdZiele.presets.size)
    }

    @Test
    fun `Kennungen werden hexadezimal gelesen, mit und ohne 0x`() {
        val ohne = SfdZiele.idPruefen("7E0")
        val mit = SfdZiele.idPruefen("0x7E0")
        val klein = SfdZiele.idPruefen("7e0")
        val leerzeichen = SfdZiele.idPruefen(" 0x7E0 ")

        assertEquals(0x7E0, ohne.wert)
        assertEquals(11, ohne.bits)
        assertTrue(ohne.ok)
        assertEquals(ohne.wert, mit.wert)
        assertEquals(ohne.wert, klein.wert)
        assertEquals(ohne.wert, leerzeichen.wert)
    }

    @Test
    fun `29-Bit-Kennung wird als solche erkannt`() {
        val p = SfdZiele.idPruefen("18DA10F1")
        assertTrue(p.ok)
        assertEquals(29, p.bits)
        assertEquals(0x18DA10F1, p.wert)

        val grenze = SfdZiele.idPruefen("1FFFFFFF")
        assertEquals(29, grenze.bits)
    }

    @Test
    fun `ungueltige Kennungen werden mit Grund abgelehnt`() {
        assertTrue(SfdZiele.idPruefen("").fehler!!.contains("leer"))
        assertTrue(SfdZiele.idPruefen("7E0Z").fehler!!.contains("Hexziffer"))
        assertTrue(SfdZiele.idPruefen("0x20000000").fehler!!.contains("29 Bit"))
        assertTrue(SfdZiele.idPruefen("123456789").fehler!!.contains("länger"))
        assertTrue(SfdZiele.idPruefen("000").fehler!!.contains("keine Adresse"))
    }

    @Test
    fun `freies Paar wird zusammengesetzt`() {
        val ergebnis = SfdZiele.frei("7E1", "7E9")
        assertTrue(ergebnis is SfdZiele.Ergebnis.Bereit)
        val ziel = (ergebnis as SfdZiele.Ergebnis.Bereit).ziel
        assertEquals(0x7E1, ziel.anfrageId)
        assertEquals(0x7E9, ziel.antwortId)
        assertEquals(11, ziel.bits)
        assertTrue(ziel.name.startsWith("frei"))
    }

    @Test
    fun `gemischte Bitbreiten werden abgelehnt`() {
        val ergebnis = SfdZiele.frei("7E0", "18DAF110")
        assertTrue(ergebnis is SfdZiele.Ergebnis.Ungueltig)
        val text = (ergebnis as SfdZiele.Ergebnis.Ungueltig).fehler
        assertTrue("nennt beide Breiten: $text", text.contains("11 Bit"))
        assertTrue("nennt beide Breiten: $text", text.contains("29 Bit"))
    }

    // --- Sitzung und Reihenfolge -----------------------------------------

    @Test
    fun `Extended Session geht vor Challenge und Token`() {
        assertEquals(
            listOf(SfdSchritt.EXTENDED, SfdSchritt.CHALLENGE),
            SfdAblauf.vorChallenge(true),
        )
        assertEquals(
            listOf(SfdSchritt.EXTENDED, SfdSchritt.ENTSPERREN),
            SfdAblauf.vorEntsperren(true),
        )
        assertEquals(
            listOf(SfdSchritt.EXTENDED, SfdSchritt.SPERREN),
            SfdAblauf.vorSperren(true),
        )
    }

    @Test
    fun `ohne Extended Session wird sie auch nicht gesendet`() {
        assertEquals(listOf(SfdSchritt.CHALLENGE), SfdAblauf.vorChallenge(false))
        assertEquals(listOf(SfdSchritt.ENTSPERREN), SfdAblauf.vorEntsperren(false))
        assertEquals(listOf(SfdSchritt.SPERREN), SfdAblauf.vorSperren(false))
    }

    @Test
    fun `NRC 7E und 7F gelten als Sitzungsproblem`() {
        assertTrue(SfdAblauf.sitzungFehlt(0x7E))
        assertTrue(SfdAblauf.sitzungFehlt(0x7F))
        assertFalse(SfdAblauf.sitzungFehlt(0x78))
        assertFalse(SfdAblauf.sitzungFehlt(0x35))
    }

    @Test
    fun `Sitzungs-NRC nennt den Schalter`() {
        val m = SfdFehler.aus(VciException.Nrc(0x31u, 0x7Eu))
        assertTrue("nennt die Sitzung: ${m.hinweis}", m.hinweis!!.contains("Extended Session"))
        assertTrue(m.hinweis!!.contains("10 03"))
    }
}
