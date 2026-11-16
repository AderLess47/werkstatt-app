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

    /**
     * Der Selbsttest-Bericht ist genau das, was `BerichtTeilen` als Datei
     * schreibt — und er enthält keinen SFD-Token.
     *
     * Zwei Prüfungen, die zusammen erst etwas belegen:
     *
     * 1. **Keine Hex-Spur.** Ein Token hat Dutzende Byte; im Bericht darf
     *    keine Hexfolge ab 17 Zeichen stehen, weder der Beispieltoken noch eine
     *    Trenner-Variante davon.
     * 2. **Der Bericht ist vollständig verglichen.** Ohne diesen Vergleich wäre
     *    Punkt 1 nur eine Aussage über die Eingabe: ein Feld, das jemand später
     *    ergänzt, könnte einen Token mitschreiben, ohne dass die Muster-Suche
     *    es merkt. Die Zeilenfolge steht deshalb als Ganzes im Test.
     *
     * Was der Test **nicht** kann: das Textfeld prüfen, in dem der Bediener den
     * Token eintippt — das ist Compose-Code und wird auf der JVM nicht
     * übersetzt. Er belegt, dass der Weg vom Zustand in die Datei nichts
     * Geheimes mitnimmt, nicht, dass der Token nirgends im Speicher steht
     * (das steht in `docs/android-app-modul.md`).
     */
    @Test
    fun `Selbsttest-Bericht enthaelt keinen SFD-Token`() {
        // So kündigt SfdAbschnitt den Unlock an: Bytezahl, nicht der Token.
        val schritt = "Sende 31 01 C0 04 → 31 01 C0 08 mit 8 Byte Token als " +
            "Multi-Frame — Frist 20 s, danach Status lesen"
        val text = AppBericht.inhalt("Pixel 6", kern, dongle, zeit, letzterSchritt = schritt)

        val hexLauf = Regex("[0-9A-Fa-f]{17,}").find(text)
        assertTrue("kein Hex-Lauf ab 17 Zeichen, gefunden: ${hexLauf?.value}", hexLauf == null)

        val token = "DEADBEEF0123456789ABCDEF"
        for (form in listOf(token, token.chunked(8).joinToString(" "))) {
            assertFalse(
                "Token darf nicht im Bericht stehen: $form",
                text.contains(form, ignoreCase = true)
            )
        }

        val erwartet = """
            VCI-Selbsttest  ·  14.11.2026 13:45
            Gerät: Pixel 6

            Kern
            [OK] Kern geladen · libvci_ffi bereit · Adressierung 0x7E0 → 0x7E8
            [OK] ISO-TP · Anfrage gesendet, Antwort zusammengesetzt

            Dongle
            [OK] Bordspannung · 12.4 V am Diagnosestecker
            [FEHLER] Steuergerät · keine Antwort — Zündung an, Bus erreichbar?

            Zuletzt gewartet auf
              $schritt

            Ergebnis: 3 von 4 Prüfungen bestanden — zuerst: Steuergerät

            ---
            Diese Datei entsteht im Selbsttest der App auf dem Telefon.
            Sie ersetzt nicht den Bericht des VCI-Checkers (vci-check --json):
            dessen sechs Prüfungen laufen auf dem Rechner gegen den Dongle.
        """.trimIndent() + "\n"

        assertEquals(erwartet, text)
    }
}
