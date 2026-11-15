package de.werkstatt.vci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.Funktion
import uniffi.vci_ffi.FunktionsKontextFfi
import uniffi.vci_ffi.VciException

/**
 * Linktest für die Funktions-Runtime und die Audit-Kette über die Sprachgrenze.
 *
 * Was die Rust-Tests nicht zeigen können: die erzeugten Kotlin-Bindings
 * kompilieren mit den neuen Typen, der Risiko-Gate kommt als Exception in
 * Kotlin an, und die Hash-Kette lässt sich von der App aus prüfen und
 * exportieren.
 */
class FunktionFfiTest {

    /** Warteschlange als Transport — wie im SFD-Linktest, ohne Plattform-Stack. */
    private class Speicher : CanTransport {
        val gesendet = mutableListOf<CanFrameFfi>()
        private val eingang = ArrayDeque<CanFrameFfi>()

        fun push(frame: CanFrameFfi) {
            eingang.addLast(frame)
        }

        override fun send(frame: CanFrameFfi) {
            gesendet.add(frame)
        }

        override fun recv(timeoutMs: UInt): CanFrameFfi? = eingang.removeFirstOrNull()

        override fun vbatMv(): UInt? = null
    }

    /** Single Frame um eine UDS-Antwort (höchstens sieben Byte). */
    private fun antwort(vararg uds: Int): CanFrameFfi {
        val daten = ByteArray(8) { 0xAA.toByte() }
        daten[0] = uds.size.toByte()
        for (i in uds.indices) daten[i + 1] = uds[i].toByte()
        return CanFrameFfi(0x7E8u, daten, 8u.toUByte())
    }

    /**
     * Definition im Format aus `core/src/content.rs`.
     *
     * Die Einrückung ist Teil des Formats (zwei Leerzeichen je Ebene) —
     * deshalb `trimIndent()` und keine Einrückung um den Text herum.
     */
    private fun yaml(risk: String, freigabe: String, evidence: String): String = """
        id: grenztest
        titel: Grenztest
        risk: $risk
        match:
          vin_praefix: WVW
        preconditions:
          - klemme15
        steps:
          - anfrage: "10 03"
            erwartet: "50 03"
            timeout_ms: 1000
        provenance:
          quelle: Test
          freigabe: $freigabe
          evidence_hash: "$evidence"
    """.trimIndent()

    private fun kontext(bestaetigung: Boolean = false) = FunktionsKontextFfi(
        vin = "WVWZZZ1JZXW000001",
        klemme15 = true,
        motorAus = true,
        bestaetigung = bestaetigung,
    )

    @Test
    fun `Funktion laeuft und schreibt in die Audit-Kette`() {
        val t = Speicher()
        t.push(antwort(0x50, 0x03, 0x00, 0x32))
        val funktion = Funktion.ausYaml("t.yaml", yaml("R1", "geprüft", "a1b2"))
        val audit = Audit()

        val ergebnis = funktion.ausfuehren(t, kontext(), audit, 1_000uL)

        assertEquals("grenztest", ergebnis.funktion)
        assertEquals(1u, ergebnis.schritte)
        assertEquals(listOf(1u), ergebnis.auditLaufnummern)
        assertEquals(1u, audit.len())
        // Die Kette prüft sich; wirft bei Manipulation.
        audit.pruefen()
        assertTrue(audit.alsJson().contains("session 10 03"))
        assertEquals(1, t.gesendet.size)
        // Anfrage als Single Frame: Länge 2, dann 10 03.
        assertEquals(0x02.toByte(), t.gesendet[0].data[0])
        assertEquals(0x10.toByte(), t.gesendet[0].data[1])
    }

    @Test
    fun `R2 ohne Bestaetigung wird abgelehnt und protokolliert`() {
        val t = Speicher()
        val funktion = Funktion.ausYaml("t.yaml", yaml("R2", "geprüft", "a1b2"))
        val audit = Audit()
        assertTrue(funktion.brauchtBestaetigung())

        try {
            funktion.ausfuehren(t, kontext(bestaetigung = false), audit, 5uL)
            fail("ohne Bestätigung darf R2 nicht laufen")
        } catch (e: VciException) {
            // JUnit 4: Meldung zuerst, dann die Bedingung.
            assertTrue(e.message.toString(), e.message.toString().contains("Bestätigung"))
        }

        assertTrue("vor der Ablehnung darf kein Rahmen rausgehen", t.gesendet.isEmpty())
        assertEquals("die Ablehnung ist ein Vorgang", 1u, audit.len())
        assertEquals("Fehler: Risiko R2 ohne Bestätigung", audit.eintraege()[0].ergebnis)
    }

    @Test
    fun `unbelegter Entwurf ab R2 wird von der Freigabe angehalten`() {
        val funktion = Funktion.ausYaml("t.yaml", yaml("R2", "unbelegt", ""))

        assertEquals("R2", funktion.risk())
        funktion.pruefeSchema()

        try {
            funktion.pruefeFreigabe()
            fail("ein unbelegter Entwurf ab R2 darf nicht freigegeben sein")
        } catch (e: VciException) {
            assertTrue(e.message.toString(), e.message.toString().contains("Freigabe"))
        }
    }
}
