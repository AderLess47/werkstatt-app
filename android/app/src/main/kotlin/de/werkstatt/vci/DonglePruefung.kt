package de.werkstatt.vci

import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.IsoTp
import java.util.Locale

/**
 * Selbsttest am angesteckten Dongle.
 *
 * Läuft auf der JVM genauso wie auf dem Gerät, weil nur `CanTransport`
 * gebraucht wird — deshalb ist die Prüfung auch testbar, ohne Hardware.
 */
object DonglePruefung {

    /**
     * Frist für die Antwort auf TesterPresent.
     *
     * Die Antwort ist ein Single Frame (`7E 00`), also ein Rahmen. ISO 14229-2
     * nennt für die erste Antwort P2_server = 50 ms; 800 ms sind die Obergrenze,
     * nach der die Prüfung aufgibt — die Reserve deckt den ersten Bulk-Transfer
     * direkt nach dem Öffnen ab, nicht einen langsamen Bus.
     */
    private const val ANTWORT_FRIST_MS = 800u

    fun pruefe(transport: CanTransport): List<Befund> =
        listOf(spannung(transport), steuergeraet(transport))

    private fun spannung(transport: CanTransport): Befund =
        try {
            val mv = transport.vbatMv()
            when {
                mv == null -> Befund(
                    "Bordspannung",
                    "Dongle meldet keine Spannung — Klemme 15/30 prüfen",
                    ok = false
                )
                mv < 9_000u || mv > 16_000u -> Befund(
                    "Bordspannung",
                    "%.1f V — außerhalb des erwarteten Bereichs (9–16 V)".format(
                        Locale.GERMANY, mv.toDouble() / 1000.0
                    ),
                    ok = false
                )
                else -> Befund(
                    "Bordspannung",
                    "%.1f V am Diagnosestecker".format(Locale.GERMANY, mv.toDouble() / 1000.0),
                    ok = true
                )
            }
        } catch (e: Throwable) {
            Befund("Bordspannung", "nicht lesbar: ${e.message ?: e.toString()}", ok = false)
        }

    /**
     * TesterPresent (0x3E 0x00) durch den Kern und wieder zurück.
     *
     * Antwortet das Steuergerät mit `7E 00`, stimmen Segmentierung, Transport
     * und Gegenrichtung. Antwortet nichts, ist der Fehler beim Dongle oder am
     * Bus — nicht in der App.
     */
    private fun steuergeraet(transport: CanTransport): Befund =
        try {
            val iso = IsoTp()
            iso.send(transport, byteArrayOf(0x3E, 0x00))
            val antwort = iso.recv(transport, ANTWORT_FRIST_MS)
            if (antwort.contentEquals(byteArrayOf(0x7E, 0x00))) {
                Befund("Steuergerät", "antwortet auf TesterPresent", ok = true)
            } else {
                Befund(
                    "Steuergerät",
                    "unerwartete Antwort: " + antwort.joinToString(" ") { "%02X".format(it) },
                    ok = false
                )
            }
        } catch (e: Throwable) {
            Befund(
                "Steuergerät",
                "keine Antwort — Zündung an, Bus erreichbar? (${e.message ?: e.toString()})",
                ok = false
            )
        }
}
