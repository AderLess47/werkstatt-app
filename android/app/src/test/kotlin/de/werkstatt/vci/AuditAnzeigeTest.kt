package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vci_ffi.Audit

/**
 * Prüft die Anzeige der Audit-Kette: Zeilen, Integritätsbefund, Exporttext.
 *
 * Die Kette selbst kommt vom Kern; hier wird nur geprüft, dass die App sie
 * vollständig und ohne Beschönigung wiedergibt — auch den Fehlerfall eines
 * Eintrags („Fehler: …“) und den Vorgängerhash.
 */
class AuditAnzeigeTest {

    @Test
    fun leere_kette_wird_als_leer_gemeldet_und_nicht_als_in_ordnung() {
        val audit = Audit()
        assertEquals(0u, audit.len())
        assertEquals(
            "Kette leer — noch kein Vorgang protokolliert.",
            AuditAnzeige.integritaet(audit),
        )
        assertTrue(AuditAnzeige.zeilen(audit).isEmpty())
    }

    @Test
    fun zwei_eintraege_halten_der_kette_und_der_export_traegt_sie() {
        val audit = Audit()
        audit.anhaengen(1uL, "WVWZZZ1JZXW000001", 0x7E0u, "session 10 03", true, "")
        audit.anhaengen(2uL, "WVWZZZ1JZXW000001", 0x7E0u, "routine 31 01", false, "NRC 0x22")

        assertEquals(2u, audit.len())
        audit.pruefen()
        assertTrue(AuditAnzeige.integritaet(audit).startsWith("Kette in Ordnung"))

        val zeilen = AuditAnzeige.zeilen(audit)
        assertEquals(2, zeilen.size)
        assertTrue(zeilen[0], zeilen[0].contains("#1"))
        assertTrue(zeilen[0], zeilen[0].contains("Erfolg"))
        assertTrue(zeilen[1], zeilen[1].contains("Fehler: NRC 0x22"))
        assertTrue("der Vorgängerhash steht in der Zeile", zeilen[1].contains("←"))

        val export = AuditAnzeige.export(audit, "Testgerät", "2026-11-15 12:00:00")
        assertTrue(export.startsWith(AuditAnzeige.KOPF))
        assertTrue(export.contains("Gerät: Testgerät"))
        assertTrue(export.contains("Einträge: 2"))
        assertTrue("die Kette geht als JSON mit", export.contains("letzter_hash"))
        assertTrue(export.contains("session 10 03"))
    }

    @Test
    fun kurzer_hash_bleibt_lesbar_und_langer_wird_gekuerzt() {
        assertEquals("abcdef", AuditAnzeige.kurz("abcdef"))
        assertEquals("abcdefghijkl…", AuditAnzeige.kurz("abcdefghijklmnop"))
    }

    /**
     * Der Export ist genau die Datei, die „Kette als Datei teilen“ schreibt:
     * `AuditAbschnitt.exportText()` reicht nichts anderes an `BerichtTeilen`
     * weiter als `AuditAnzeige.export(...)`.
     *
     * Geprüft wird deshalb hier, was in dieser Datei steht:
     *
     * 1. **Genau Kopf und Kette.** Der Text ist Zeile für Zeile verglichen —
     *    ein Feld, das jemand ergänzt, fällt auf. Damit ist auch belegt, dass
     *    die App der Kette nichts hinzufügt und nichts weglässt.
     * 2. **Keine Hexfolge außer den Hashes.** Im Nachweis stehen SHA-256-Werte
     *    mit 64 Zeichen; jede andere lange Hexfolge wäre ein Token oder ein
     *    Schlüssel. Der Beispieltoken wird zusätzlich in Reinform und in
     *    Blockgruppierung gesucht.
     *
     * Was der Test **nicht** kann: `AuditAbschnitt` übersetzen — der Abschnitt
     * ist Compose-Code und wird auf der JVM nicht gebaut. Dass die Datei nur
     * diesen Text enthält, ist am Aufruf abgelesen (nichts weiter wird
     * übergeben), nicht durch diesen Test bewiesen.
     */
    @Test
    fun export_enthaelt_nur_ketteninhalt_und_keine_geheimnisse() {
        val audit = Audit()
        audit.anhaengen(
            1_700_000_000_000uL, "WVWZZZ1JZXW000001", 0x7E0u,
            "SFD entsperren 31 01 C0 08", true, ""
        )
        audit.anhaengen(
            1_700_000_001_000uL, "WVWZZZ1JZXW000001", 0x7E0u,
            "SFD sperren 31 01 C0 09", false, "abgelehnt (Dienst 0x31, NRC 0x35: invalidKey)"
        )

        val export = AuditAnzeige.export(audit, "Testgerät", "2026-11-16 09:30:00")

        val erwartet = buildString {
            appendLine(AuditAnzeige.KOPF)
            appendLine("Gerät: Testgerät")
            appendLine("Zeitpunkt: 2026-11-16 09:30:00")
            appendLine("Einträge: 2")
            appendLine("Prüfung: ${AuditAnzeige.integritaet(audit)}")
            appendLine(
                "Katalogstand: Inhalte aus content/funktionen " +
                    "(Freigabe je Inhalt im Feld provenance.freigabe)"
            )
            appendLine()
            append(audit.alsJson())
        }
        assertEquals(erwartet, export)
        assertTrue(
            "der Prüfbefund steht im Kopf",
            export.contains("Kette in Ordnung: 2 Einträge")
        )

        // Hashes sind 64 Zeichen breit; eine andere lange Hexfolge wäre ein
        // Geheimnis, das jemand mitprotokolliert hat.
        val laengen = Regex("[0-9A-Fa-f]{16,}").findAll(export).map { it.value.length }.toSet()
        assertEquals("nur Hashes in Hex, gefunden: $laengen", setOf(64), laengen)

        val token = "DEADBEEF0123456789ABCDEF"
        for (form in listOf(token, token.chunked(8).joinToString(" "))) {
            assertFalse(
                "Token darf nicht im Nachweis stehen: $form",
                export.contains(form, ignoreCase = true)
            )
        }
    }
}
