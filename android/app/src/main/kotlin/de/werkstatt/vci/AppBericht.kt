package de.werkstatt.vci

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Selbsttest-Bericht, den die App **selbst** schreibt.
 *
 * Der Techniker fügt nichts ein: Kern- und Dongle-Prüfung laufen auf dem
 * Telefon ohnehin, und daraus entsteht diese Datei — dieselbe Ablage und
 * derselbe Teilen-Dialog wie beim Bericht des Checkers.
 *
 * Abgrenzung, die in der Datei auch drinsteht: das ist **nicht** der Bericht
 * von `vci-check --json`. Der Checker läuft auf dem Rechner und prüft die
 * Messkette in sechs Schritten; hier stehen die Prüfungen, die die App auf
 * diesem Gerät wirklich gefahren hat. Zwei Berichte, zwei Wahrheiten — sie
 * werden nicht vermischt.
 *
 * Ohne Android, damit Name und Inhalt auf der JVM prüfbar sind.
 */
object AppBericht {

    private val imNamen = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val lesbar = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Dateiname, z. B. `vci-selbsttest-Pixel-6-20261114-1345.txt`. */
    fun name(geraet: String, zeit: LocalDateTime): String =
        "vci-selbsttest-${BerichtDatei.entschaerft(geraet)}-${zeit.format(imNamen)}.txt"

    /**
     * Inhalt der Datei.
     *
     * [kern] und [dongle] dürfen `null` sein: die Dongle-Prüfung läuft erst auf
     * Knopfdruck, und ein Bericht ohne sie soll trotzdem sagen, was er weiß —
     * mit dem ausdrücklichen Vermerk „nicht gelaufen“.
     *
     * [letzterSchritt] ist der Schritt, den die App zuletzt angekündigt hat
     * („Warte auf die Antwort auf 22 F190 …“). Er steht auch dann in der Datei,
     * wenn nichts abgebrochen ist — dann weiß der Support, wie weit die App
     * gekommen ist. Ohne Angabe steht dort „nicht erfasst“, nicht ein geratener
     * Schritt.
     */
    fun inhalt(
        geraet: String,
        kern: List<Befund>?,
        dongle: List<Befund>?,
        zeit: LocalDateTime,
        letzterSchritt: String? = null,
    ): String {
        val alle = (kern ?: emptyList()) + (dongle ?: emptyList())
        val bestanden = alle.count { it.ok }

        val bau = StringBuilder()
        bau.appendLine("VCI-Selbsttest  ·  ${zeit.format(lesbar)}")
        bau.appendLine("Gerät: $geraet")
        bau.appendLine()
        bau.appendLine("Kern")
        abschnitt(bau, kern)
        bau.appendLine()
        bau.appendLine("Dongle")
        abschnitt(bau, dongle)
        bau.appendLine()
        bau.appendLine("Zuletzt gewartet auf")
        bau.appendLine("  ${letzterSchritt ?: "nicht erfasst"}")
        bau.appendLine()
        bau.appendLine(ergebnis(alle, bestanden))
        bau.appendLine()
        bau.appendLine("---")
        bau.appendLine("Diese Datei entsteht im Selbsttest der App auf dem Telefon.")
        bau.appendLine("Sie ersetzt nicht den Bericht des VCI-Checkers (vci-check --json):")
        bau.appendLine("dessen sechs Prüfungen laufen auf dem Rechner gegen den Dongle.")
        return bau.toString()
    }

    /** Ergebnis-Satz für Kopf und Datei. */
    fun ergebnis(alle: List<Befund>): String =
        ergebnis(alle, alle.count { it.ok })

    private fun ergebnis(alle: List<Befund>, bestanden: Int): String = when {
        alle.isEmpty() -> "Ergebnis: keine Prüfung gelaufen"
        bestanden == alle.size -> "Ergebnis: alle $bestanden Prüfungen bestanden"
        else -> "Ergebnis: $bestanden von ${alle.size} Prüfungen bestanden — zuerst: " +
            alle.first { !it.ok }.titel
    }

    private fun abschnitt(bau: StringBuilder, befunde: List<Befund>?) {
        if (befunde.isNullOrEmpty()) {
            bau.appendLine("  (nicht gelaufen)")
            return
        }
        for (b in befunde) {
            bau.appendLine("[${if (b.ok) "OK" else "FEHLER"}] ${b.titel} · ${b.text}")
        }
    }
}
