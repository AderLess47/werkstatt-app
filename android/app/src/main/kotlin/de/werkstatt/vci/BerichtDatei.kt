package de.werkstatt.vci

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Der Checker-Bericht als Datei — Name und Inhalt.
 *
 * Zweck: die Werkstatt soll den Bericht an den Support schicken können, ohne
 * ihn abzutippen. Die Datei ist deshalb für Menschen **und** für die
 * Fehlersuche brauchbar: oben die Auswertung, die der Techniker am Telefon
 * vorliest, unten der unveränderte JSON-Bericht, den der Support auswertet.
 *
 * Bewusst ohne Android: Name und Inhalt entstehen aus [VciBericht] und einem
 * Zeitpunkt, damit sie auf der JVM prüfbar sind. Das Schreiben und Teilen macht
 * `BerichtTeilen`.
 */
object BerichtDatei {

    private val imNamen = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val lesbar = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /**
     * Dateiname, z. B. `vci-bericht-sim-vci-20261114-1345.txt`.
     *
     * Der Gerätename kommt vom Dongle und ist damit fremder Text: Nur Zeichen,
     * die jedes Dateisystem und jeder Mailanhang vertragen, bleiben stehen.
     * Sonst könnte ein Gerät mit `/` im Namen den Pfad zerlegen.
     */
    fun name(bericht: VciBericht, zeit: LocalDateTime): String =
        "vci-bericht-${entschaerft(bericht.geraet)}-${zeit.format(imNamen)}.txt"

    /**
     * Inhalt der Datei: Kopf, Ergebnis-Satz, jede Prüfung mit Messwert und
     * Hinweis, dann der Original-JSON-Bericht.
     */
    fun inhalt(bericht: VciBericht, json: String, zeit: LocalDateTime): String {
        val bau = StringBuilder()
        bau.appendLine("VCI-Bericht  ·  ${zeit.format(lesbar)}")
        bau.appendLine()
        bau.appendLine("Gerät:      ${bericht.geraet}")
        bau.appendLine("Firmware:   ${bericht.firmware}")
        bau.appendLine("Ergebnis:   ${bericht.ergebnis.anzeige}")
        bau.appendLine("Resync:     ${bericht.resyncs}")
        bau.appendLine("CRC-Fehler: ${bericht.crcFehler}")
        bau.appendLine()
        bau.appendLine(bericht.zusammenfassung)
        bau.appendLine()
        for (p in bericht.pruefungen) {
            bau.appendLine("[${p.status.anzeige}] ${p.id} · ${p.meldung}")
            bau.appendLine("        Wert: ${p.messwert}")
            if (p.hinweis.isNotBlank()) bau.appendLine("        Hinweis: ${p.hinweis}")
        }
        bau.appendLine()
        bau.appendLine("--- Original-Bericht (JSON) ---")
        bau.appendLine(json.trim())
        return bau.toString()
    }

    /** Nur `A–Z`, `a–z`, `0–9`, `-`, `_` und `.` — alles andere wird zu `_`. */
    internal fun entschaerft(geraet: String): String {
        val roh = geraet.trim().map {
            if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.') {
                it
            } else {
                '_'
            }
        }.joinToString("")
        val ohneRaender = roh.trim('.', '_', '-')
        return if (ohneRaender.isEmpty()) "vci" else ohneRaender.take(40)
    }
}
