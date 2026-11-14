package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Prüft den Leser gegen genau die Form, die `chk_to_json` schreibt
 * (`j2534/src/checker.c`, Vertrag in `docs/vci-checker.md` §3).
 */
class VciBerichtTest {

    private val beispiel = """
        {"device":"sim-vci","firmware":"1.4","verdict":"fail","resyncs":0,"crc_errors":0,
         "checks":[
          {"id":"device","status":"ok","value":0,"unit":"","message":"VCI antwortet","hint":""},
          {"id":"vbat","status":"fail","value":11500,"unit":"mV","message":"Bordspannung 11.50 V","hint":"Ladegerät anschließen"},
          {"id":"bus","status":"warn","value":140,"unit":"ms","message":"Antwort in 140 ms","hint":"Zündung prüfen"}
         ]}
    """.trimIndent()

    @Test
    fun `liest Kopf und Pruefungen des Berichts`() {
        val b = VciBericht.ausJson(beispiel)

        assertEquals("sim-vci", b.geraet)
        assertEquals("1.4", b.firmware)
        assertEquals(VciStatus.FEHLER, b.ergebnis)
        assertEquals(0L, b.resyncs)
        assertEquals(0L, b.crcFehler)
        assertEquals(3, b.pruefungen.size)
    }

    @Test
    fun `liest Wert Einheit und Hinweis je Pruefung`() {
        val vbat = VciBericht.ausJson(beispiel).pruefungen[1]

        assertEquals("vbat", vbat.id)
        assertEquals(VciStatus.FEHLER, vbat.status)
        assertEquals(11500L, vbat.wert)
        assertEquals("mV", vbat.einheit)
        assertEquals("11500 mV", vbat.messwert)
        assertEquals("Bordspannung 11.50 V", vbat.meldung)
        assertEquals("Ladegerät anschließen", vbat.hinweis)
    }

    @Test
    fun `Pruefung ohne Einheit zeigt nur den Wert`() {
        val device = VciBericht.ausJson(beispiel).pruefungen[0]

        assertEquals("0", device.messwert)
    }

    @Test
    fun `alle vier Statuswerte werden abgebildet`() {
        assertEquals(VciStatus.OK, VciStatus.aus("ok"))
        assertEquals(VciStatus.WARNUNG, VciStatus.aus("warn"))
        assertEquals(VciStatus.FEHLER, VciStatus.aus("fail"))
        assertEquals(VciStatus.UEBERSPRUNGEN, VciStatus.aus("skip"))
        assertEquals(VciStatus.OK, VciStatus.aus(" OK "))   // tolerant gegen Leerraum
        assertNull(VciStatus.aus("unbekannt"))
    }

    @Test
    fun `Handlungsbedarf enthaelt Warnung und Fehler aber nicht OK`() {
        val b = VciBericht.ausJson(beispiel)

        assertEquals(listOf("vbat", "bus"), b.handlungsbedarf.map { it.id })
    }

    @Test
    fun `Zusammenfassung nennt den ersten Handlungshinweis`() {
        val b = VciBericht.ausJson(beispiel)

        assertEquals("VCI nicht in Ordnung — Ladegerät anschließen", b.zusammenfassung)
    }

    @Test
    fun `Zusammenfassung bei gruenem Bericht ohne Handlungsbedarf`() {
        val gruen = """
            {"device":"sim-vci","firmware":"1.4","verdict":"ok","resyncs":0,"crc_errors":0,
             "checks":[{"id":"device","status":"ok","value":0,"unit":"","message":"VCI antwortet","hint":""}]}
        """.trimIndent()

        assertEquals("VCI in Ordnung — 1 von 1 Prüfungen bestanden", VciBericht.ausJson(gruen).zusammenfassung)
    }

    @Test
    fun `widerspruechlicher Bericht wird nicht geglaettet`() {
        // Kann ein echter Checker nicht liefern (er rechnet verdict aus den
        // Prüfungen); die App folgt trotzdem dem Feld und entscheidet nichts nach.
        val widerspruch = """
            {"device":"sim-vci","firmware":"1.4","verdict":"ok","resyncs":0,"crc_errors":0,
             "checks":[{"id":"vbat","status":"fail","value":11500,"unit":"mV",
                        "message":"Bordspannung 11.50 V","hint":"Ladegerät anschließen"}]}
        """.trimIndent()

        val b = VciBericht.ausJson(widerspruch)

        assertEquals(VciStatus.OK, b.ergebnis)
        assertEquals(1, b.handlungsbedarf.size)
        assertEquals("VCI in Ordnung — 0 von 1 Prüfungen bestanden", b.zusammenfassung)
    }

    @Test
    fun `unbekannte Felder werden ignoriert`() {
        val neu = """
            {"device":"sim-vci","firmware":"1.4","verdict":"ok","resyncs":0,"crc_errors":0,
             "dauer_ms":1234,
             "checks":[{"id":"device","status":"ok","value":0,"unit":"","message":"ok","hint":"","extra":true}]}
        """.trimIndent()

        assertEquals(1, VciBericht.ausJson(neu).pruefungen.size)
    }

    @Test
    fun `Escapes im Text werden aufgeloest`() {
        val esc = """
            {"device":"sim-vci","firmware":"1.4","verdict":"warn","resyncs":0,"crc_errors":0,
             "checks":[{"id":"bus","status":"warn","value":0,"unit":"",
                        "message":"kein Ger\u00e4t \u0022antwortet\u0022 \n","hint":"Kabel \"prüfen\""}]}
        """.trimIndent()

        val p = VciBericht.ausJson(esc).pruefungen[0]
        assertEquals("kein Gerät \"antwortet\" \n", p.meldung)
        assertEquals("Kabel \"prüfen\"", p.hinweis)
    }

    @Test
    fun `abgeschnittener Bericht wird mit Klartext abgelehnt`() {
        val halb = beispiel.take(beispiel.length / 2)
        try {
            VciBericht.ausJson(halb)
            fail("halber Bericht darf nicht durchgehen")
        } catch (e: VciBericht.Fehler) {
            assertTrue(e.message!!.startsWith("Kein gültiges JSON"))
        }
    }

    @Test
    fun `fehlendes Feld wird benannt`() {
        val ohneChecks = """{"device":"x","firmware":"1","verdict":"ok","resyncs":0,"crc_errors":0}"""
        try {
            VciBericht.ausJson(ohneChecks)
            fail("Bericht ohne checks darf nicht durchgehen")
        } catch (e: VciBericht.Fehler) {
            assertEquals("Feld \"checks\" fehlt oder ist keine Liste.", e.message)
        }
    }

    @Test
    fun `leerer Text wird abgelehnt`() {
        try {
            VciBericht.ausJson("   ")
            fail("leerer Bericht darf nicht durchgehen")
        } catch (e: VciBericht.Fehler) {
            assertEquals("Der Bericht ist leer.", e.message)
        }
    }
}
