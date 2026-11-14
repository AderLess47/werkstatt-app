package de.werkstatt.vci

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft Name und Inhalt der Datei, die die Werkstatt an den Support schickt. */
class BerichtDateiTest {

    private val zeit = LocalDateTime.of(2026, 11, 14, 13, 45)

    private val json = """
        {"device":"sim-vci","firmware":"1.4","verdict":"fail","resyncs":2,"crc_errors":1,
         "checks":[
          {"id":"device","status":"ok","value":0,"unit":"","message":"VCI antwortet","hint":""},
          {"id":"vbat","status":"fail","value":11500,"unit":"mV","message":"Bordspannung 11.50 V","hint":"Ladegerät anschließen"},
          {"id":"bus","status":"skip","value":0,"unit":"","message":"kein Bus","hint":""}
         ]}
    """.trimIndent()

    private fun bericht() = VciBericht.ausJson(json)

    @Test
    fun `Dateiname nennt Geraet und Zeitpunkt`() {
        assertEquals("vci-bericht-sim-vci-20261114-1345.txt", BerichtDatei.name(bericht(), zeit))
    }

    @Test
    fun `Sonderzeichen im Geraetenamen werden entschaerft`() {
        val fremd = json.replace("\"sim-vci\"", "\"ACME/VCI #3\"")
        val name = BerichtDatei.name(VciBericht.ausJson(fremd), zeit)

        assertEquals("vci-bericht-ACME_VCI__3-20261114-1345.txt", name)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(" "))
        assertFalse(name.contains("#"))
    }

    @Test
    fun `leerer Geraetename faellt auf vci zurueck`() {
        val ohne = json.replace("\"sim-vci\"", "\"\"")

        assertEquals("vci-bericht-vci-20261114-1345.txt", BerichtDatei.name(VciBericht.ausJson(ohne), zeit))
    }

    @Test
    fun `Inhalt nennt Ergebnis und jede Pruefung`() {
        val text = BerichtDatei.inhalt(bericht(), json, zeit)

        assertTrue(text.contains("VCI-Bericht  ·  14.11.2026 13:45"))
        assertTrue(text.contains("Gerät:      sim-vci"))
        assertTrue(text.contains("Firmware:   1.4"))
        assertTrue(text.contains("Ergebnis:   FEHLER"))
        assertTrue(text.contains("Resync:     2"))
        assertTrue(text.contains("CRC-Fehler: 1"))
        assertTrue(text.contains("VCI nicht in Ordnung — Ladegerät anschließen"))
        assertTrue(text.contains("[OK] device · VCI antwortet"))
        assertTrue(text.contains("[FEHLER] vbat · Bordspannung 11.50 V"))
        assertTrue(text.contains("        Wert: 11500 mV"))
        assertTrue(text.contains("[ÜBERSPRUNGEN] bus · kein Bus"))
    }

    @Test
    fun `Hinweis steht nur da wenn der Bericht einen hat`() {
        val text = BerichtDatei.inhalt(bericht(), json, zeit)

        assertTrue(text.contains("        Hinweis: Ladegerät anschließen"))
        assertEquals(1, Regex("^\\s+Hinweis: .*$", RegexOption.MULTILINE).findAll(text).count())
    }

    @Test
    fun `Original-JSON steht unveraendert am Ende`() {
        val text = BerichtDatei.inhalt(bericht(), "\n  $json  \n", zeit)

        assertTrue(text.endsWith("--- Original-Bericht (JSON) ---\n${json.trim()}\n"))
    }
}
