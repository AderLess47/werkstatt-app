package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vci_ffi.Audit

/**
 * Prüft die Anzeige der Audit-Kette: Zeilen, Integritätsbefund, Exporttext.
 *
 * Die Kette selbst kommt aus dem Kern; hier wird nur geprüft, dass die App sie
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
}
