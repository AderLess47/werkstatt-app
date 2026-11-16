package de.werkstatt.vci

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.CanFrameFfi
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.Funktion

/**
 * Prüft die Rechnung hinter dem Abschnitt „Service-Funktionen“: Vorbedingungen,
 * Freigabesperre, Schrittauswertung.
 *
 * Der Transport ist hier ein Speicher, kein Dongle — geprüft wird die Logik der
 * App gegen den **echten** Kern, und die Katalogdateien sind dieselben, die der
 * Bau in die Assets kopiert. Was hier nicht geht: zeigen, dass ein Steuergerät
 * so antwortet. Das bleibt der Prüfung am Fahrzeug vorbehalten.
 */
class ServiceFunktionenTest {

    /** Antworten aus der Konserve; jeder gesendete Rahmen wird mitgeschrieben. */
    private class Speicher(
        private val antworten: List<ByteArray>,
        private val vbat: UInt? = null,
    ) : CanTransport {
        val gesendet = mutableListOf<CanFrameFfi>()
        private var zeiger = 0

        override fun send(frame: CanFrameFfi) {
            gesendet += frame
        }

        override fun recv(timeoutMs: UInt): CanFrameFfi? {
            val nutzlast = antworten.getOrNull(zeiger++) ?: return null
            // ISO-TP-Single-Frame: erstes Byte ist die Länge, dann die Nutzlast.
            val feld = ByteArray(8)
            feld[0] = nutzlast.size.toByte()
            nutzlast.copyInto(feld, 1)
            return CanFrameFfi(0x7E8u, feld, 8u)
        }

        override fun vbatMv(): UInt? = vbat
    }

    /** Die echten Kataloginhalte — je nach Arbeitsverzeichnis eine Ebene höher. */
    private fun katalog(): List<Funktion> {
        val ordner = listOf("content/funktionen", "../content/funktionen", "../../content/funktionen")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
        assertNotNull(
            "Katalogverzeichnis nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})",
            ordner,
        )
        return ordner!!
            .listFiles { datei -> datei.name.endsWith(".yaml") }!!
            .sortedBy { it.name }
            .map { Funktion.ausYaml(it.name, it.readText()) }
    }

    /** Kleiner Ablauf mit Freigabe „geprüft“, damit der Lauf nicht gesperrt wird. */
    private fun geprueft(risk: String = "R1", mitSpannung: Boolean = false): Funktion {
        val bedingungen = if (mitSpannung) {
            "  - klemme15\n  - bordspannung:\n      min_mv: 11800\n      max_mv: 14400\n"
        } else {
            "  - klemme15\n"
        }
        val text = "id: test-ablauf\ntitel: Testablauf\nrisk: $risk\nmatch:\n  vin_praefix: WVW\n" +
            "preconditions:\n$bedingungen" +
            "steps:\n  - anfrage: \"10 03\"\n    erwartet: \"50 03\"\n    timeout_ms: 1000\n" +
            "provenance:\n  quelle: Test\n  freigabe: geprüft\n  evidence_hash: \"a1b2\"\n"
        return Funktion.ausYaml("test.yaml", text)
    }

    @Test
    fun alle_fuenf_kataloginhalte_laden_mit_ihrer_risikoklasse() {
        val funktionen = katalog()
        assertEquals(5, funktionen.size)
        assertEquals(
            "die Kennungen müssen eindeutig sein",
            funktionen.size,
            funktionen.map { it.id() }.toSet().size,
        )
        val risks = funktionen.map { it.risk() }
        assertEquals("genau ein Ablauf ohne Bestätigung (Ölservice)", 1, risks.count { it == "R1" })
        assertEquals("vier Abläufe ab R2", 4, risks.count { it == "R2" })
        assertTrue(risks.all { it.matches(Regex("R[0-4]")) })
    }

    @Test
    fun vorbedingungen_stehen_vorab_mit_messwert_und_grenzen() {
        val oelservice = katalog().first { it.risk() == "R1" }
        val ohne = FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = null)

        val zeilen = Vorabpruefung.zeilen(oelservice, ohne)
        assertTrue(zeilen.any { it.titel == "Klemme 15" && it.erfuellt })
        assertTrue(zeilen.any { it.titel == "Motor aus" && it.erfuellt })
        val spannung = zeilen.first { it.titel == "Bordspannung" }
        assertFalse("ohne Messwert ist die Bedingung offen", spannung.erfuellt)
        assertTrue(spannung.text, spannung.text.contains("nicht messbar"))
        assertFalse(Vorabpruefung.bereit(oelservice, ohne))

        val imFenster = FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = 12_300u)
        assertTrue(Vorabpruefung.bereit(oelservice, imFenster))
        assertTrue(
            Vorabpruefung.zeilen(oelservice, imFenster).first { it.titel == "Bordspannung" }
                .text.contains("12,30 V"),
        )

        val zuNiedrig = Vorabpruefung.zeilen(
            oelservice,
            FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = 9_800u),
        ).first { it.titel == "Bordspannung" }
        assertFalse(zuNiedrig.erfuellt)
        assertTrue(zuNiedrig.text, zuNiedrig.text.contains("zu niedrig"))
    }

    @Test
    fun klemme15_und_motor_halten_den_lauf_an() {
        val oelservice = katalog().first { it.risk() == "R1" }
        val ohneZuendung = FahrzeugZustand(klemme15 = false, motorAus = true, vbatMv = 12_300u)
        val zeilen = Vorabpruefung.zeilen(oelservice, ohneZuendung)
        assertFalse(zeilen.first { it.titel == "Klemme 15" }.erfuellt)
        assertFalse(Vorabpruefung.bereit(oelservice, ohneZuendung))

        val mitMotor = FahrzeugZustand(klemme15 = true, motorAus = false, vbatMv = 12_300u)
        assertFalse(
            Vorabpruefung.zeilen(oelservice, mitMotor).first { it.titel == "Motor aus" }.erfuellt,
        )
    }

    @Test
    fun unbelegte_r2_entwuerfe_werden_gesperrt_und_senden_nichts() {
        val epb = katalog().first { it.id() == "epb-wartungsmodus" }
        val sperre = FunktionsBediener.sperre(epb)
        assertNotNull("R2 mit freigabe: unbelegt muss gesperrt sein", sperre)

        val speicher = Speicher(listOf(byteArrayOf(0x50, 0x03)))
        val audit = Audit()
        val lauf = FunktionsBediener.laufen(
            funktion = epb,
            transport = speicher,
            zustand = FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = 12_500u),
            bestaetigung = true,
            audit = audit,
            zeitMs = 1uL,
        )

        assertNotNull(lauf.gesperrt)
        assertFalse(lauf.erfolgreich)
        assertTrue("kein Rahmen darf hinausgehen", speicher.gesendet.isEmpty())
        assertTrue(lauf.schritte.all { it.ausgang == "gesperrt" })
        assertEquals("eine Sperre ist kein Vorgang in der Kette", 0u, audit.len())
    }

    @Test
    fun der_r1_entwurf_ist_frei_aber_ohne_spannung_startet_er_nicht() {
        val oelservice = katalog().first { it.risk() == "R1" }
        assertNull("R1 ohne Bestätigungspflicht ist nicht gesperrt", FunktionsBediener.sperre(oelservice))

        val speicher = Speicher(listOf(byteArrayOf(0x50, 0x03)))
        val audit = Audit()
        val lauf = FunktionsBediener.laufen(
            funktion = oelservice,
            transport = speicher,
            zustand = FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = null),
            bestaetigung = false,
            audit = audit,
            zeitMs = 1uL,
        )

        assertNotNull(lauf.fehler)
        assertTrue(lauf.fehler!!, lauf.fehler!!.contains("Vorbedingung"))
        assertTrue("ohne Messung geht kein Rahmen raus", speicher.gesendet.isEmpty())
        assertEquals("auch die Ablehnung steht in der Kette", 1u, audit.len())
    }

    @Test
    fun erfolgreicher_lauf_zeigt_jeden_schritt_und_seine_laufnummer() {
        val speicher = Speicher(listOf(byteArrayOf(0x50, 0x03, 0x00, 0x32)))
        val audit = Audit()
        val lauf = FunktionsBediener.laufen(
            funktion = geprueft(),
            transport = speicher,
            zustand = FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = null),
            bestaetigung = false,
            audit = audit,
            zeitMs = 1_000uL,
        )

        assertNull(lauf.gesperrt)
        assertNull(lauf.fehler)
        assertTrue(lauf.erfolgreich)
        assertEquals(1, lauf.schritte.size)
        assertTrue("Schritt gilt als Erfolg", lauf.schritte[0].ok)
        assertEquals("10 03", lauf.schritte[0].anfrage)
        assertEquals(listOf(1u), lauf.laufnummern)
        assertEquals(1u, audit.len())
        assertTrue(audit.eintraege()[0].dienst.contains("10 03"))
    }

    @Test
    fun r2_ohne_bestaetigung_wird_abgelehnt_und_mit_bestaetigung_ausgefuehrt() {
        val audit = Audit()
        val ohne = FunktionsBediener.laufen(
            geprueft("R2"),
            Speicher(listOf(byteArrayOf(0x50, 0x03))),
            FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = null),
            false,
            audit,
            1uL,
        )
        assertNotNull(ohne.fehler)
        assertTrue(ohne.fehler!!, ohne.fehler!!.contains("Bestätigung"))

        val speicher = Speicher(listOf(byteArrayOf(0x50, 0x03, 0x00, 0x32)))
        val mit = FunktionsBediener.laufen(
            geprueft("R2"),
            speicher,
            FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = null),
            true,
            audit,
            2uL,
        )
        assertNull(mit.fehler)
        assertTrue(mit.erfolgreich)
        assertEquals(1, speicher.gesendet.count { it.data[1] == 0x10.toByte() })
    }

    @Test
    fun eine_falsche_antwort_markiert_den_schritt_als_fehler() {
        val speicher = Speicher(listOf(byteArrayOf(0x50, 0x01)))
        val lauf = FunktionsBediener.laufen(
            geprueft(),
            speicher,
            FahrzeugZustand(klemme15 = true, motorAus = true, vbatMv = null),
            false,
            Audit(),
            1uL,
        )

        assertNotNull(lauf.fehler)
        assertTrue(lauf.fehler!!, lauf.fehler!!.contains("Antwort beginnt nicht mit 50 03"))
        // Die Zeile nennt den Schritt ab 1 — die Nummer kommt aus der Schrittliste,
        // nicht aus der Fehlermeldung des Kerns.
        assertEquals(1u, lauf.schritte[0].nummer)
        assertFalse(lauf.schritte[0].ok)
    }
}
