package de.werkstatt.vci

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft den Selbsttest-Bericht, den die App ohne Eingabe erzeugt. */
class AppBerichtTest {

    private val zeit = LocalDateTime.of(2026, 11, 14, 13, 45)

    private val kern = listOf(
        Befund("Kern geladen", "libvci_ffi bereit · Adressierung 0x7E0 → 0x7E8", ok = true),
        Befund("ISO-TP", "Anfrage gesendet, Antwort zusammengesetzt", ok = true),
    )

    private val dongle = listOf(
        Befund("Bordspannung", "12.4 V am Diagnosestecker", ok = true),
        Befund("Steuergerät", "keine Antwort — Zündung an, Bus erreichbar?", ok = false),
    )

    @Test
    fun `Dateiname nennt Geraet und Zeitpunkt`() {
        assertEquals(
            "vci-selbsttest-Pixel_6-20261114-1345.txt",
            AppBericht.name("Pixel 6", zeit)
        )
    }

    @Test
    fun `Dateiname entschaerft Sonderzeichen`() {
        val name = AppBericht.name("ACME/VCI #3", zeit)

        assertEquals("vci-selbsttest-ACME_VCI__3-20261114-1345.txt", name)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(" "))
    }

    @Test
    fun `Inhalt trennt Kern und Dongle`() {
        val text = AppBericht.inhalt("Pixel 6 (Android 14)", kern, dongle, zeit)

        assertTrue(text.contains("VCI-Selbsttest  ·  14.11.2026 13:45"))
        assertTrue(text.contains("Gerät: Pixel 6 (Android 14)"))
        assertTrue(text.contains("Kern"))
        assertTrue(text.contains("[OK] Kern geladen · libvci_ffi bereit · Adressierung 0x7E0 → 0x7E8"))
        assertTrue(text.contains("Dongle"))
        assertTrue(text.contains("[FEHLER] Steuergerät · keine Antwort — Zündung an, Bus erreichbar?"))
    }

    @Test
    fun `nicht gelaufener Dongle steht als nicht gelaufen`() {
        val text = AppBericht.inhalt("Pixel 6", kern, null, zeit)

        assertTrue(text.contains("Dongle\n  (nicht gelaufen)"))
        // Nur der Dongle fehlt, der Kern ist gelaufen.
        assertEquals(1, Regex("nicht gelaufen").findAll(text).count())
    }

    @Test
    fun `Ergebnis zaehlt die bestandenen Pruefungen`() {
        assertEquals("Ergebnis: 3 von 4 Prüfungen bestanden — zuerst: Steuergerät",
            AppBericht.ergebnis(kern + dongle))
        assertEquals("Ergebnis: alle 2 Prüfungen bestanden", AppBericht.ergebnis(kern))
        assertEquals("Ergebnis: keine Prüfung gelaufen", AppBericht.ergebnis(emptyList()))
    }

    @Test
    fun `Inhalt nennt das Ergebnis`() {
        val text = AppBericht.inhalt("Pixel 6", kern, dongle, zeit)

        assertTrue(text.contains("Ergebnis: 3 von 4 Prüfungen bestanden — zuerst: Steuergerät"))
    }

    @Test
    fun `Inhalt nennt den letzten Warteschritt`() {
        val text = AppBericht.inhalt(
            "Pixel 6", kern, dongle, zeit,
            letzterSchritt = "Warte auf die Antwort auf 22 F190 — Frist 1,5 s je Rahmen"
        )

        assertTrue(
            text.contains(
                "Zuletzt gewartet auf\n  Warte auf die Antwort auf 22 F190 — Frist 1,5 s je Rahmen"
            )
        )
    }

    @Test
    fun `ohne Warteschritt steht nicht erfasst`() {
        val text = AppBericht.inhalt("Pixel 6", kern, dongle, zeit)

        assertTrue(text.contains("Zuletzt gewartet auf\n  nicht erfasst"))
    }

    @Test
    fun `Inhalt sagt dass es nicht der Checker-Bericht ist`() {
        val text = AppBericht.inhalt("Pixel 6", kern, dongle, zeit)

        assertTrue(text.contains("Sie ersetzt nicht den Bericht des VCI-Checkers (vci-check --json):"))
        assertFalse(text.contains("checks"))
    }
}
