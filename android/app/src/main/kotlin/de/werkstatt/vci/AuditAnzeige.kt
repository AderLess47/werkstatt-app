package de.werkstatt.vci

import java.util.Locale
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.VciException

/**
 * Aufbereitung der Audit-Kette für den Bildschirm und für den Nachweis.
 *
 * Die Kette selbst rechnet der Kern (`core/src/audit.rs`); hier wird nichts
 * nachgerechnet und nichts geglättet. Wenn die Prüfung fehlschlägt, steht der
 * Grund da — auch dann, wenn er unangenehm ist.
 */
object AuditAnzeige {

    /** Kopfzeile des Exports; ändert sich nicht, damit Nachweise vergleichbar bleiben. */
    const val KOPF = "Werkstatt VCI — Audit-Kette"

    /** Ein Hash vollständig wäre 64 Zeichen breit; in einer Zeile reichen zwölf. */
    fun kurz(hash: String): String =
        if (hash.length <= 12) hash else hash.take(12) + "…"

    /**
     * Eine Zeile je Eintrag: Laufnummer, Steuergerät, Dienst, Ergebnis, Hash.
     *
     * Der Vorgängerhash steht mit dabei: nur so ist am Bildschirm zu sehen,
     * dass jeder Eintrag am vorigen hängt, ohne die Datei zu öffnen.
     */
    fun zeilen(audit: Audit): List<String> = audit.eintraege().map { eintrag ->
        // `String.format` kennt `UInt`/`ULong` nicht — ohne die Umrechnung
        // fliegt eine `IllegalFormatConversionException` mitten im Zeichnen.
        String.format(
            Locale.GERMANY,
            "#%d · %d ms · 0x%04X · %s · %s · Hash %s ← %s",
            eintrag.laufnummer.toLong(),
            eintrag.zeitMs.toLong(),
            eintrag.steuergeraet.toLong(),
            eintrag.dienst,
            eintrag.ergebnis,
            kurz(eintrag.hash),
            kurz(eintrag.vorherigerHash),
        )
    }

    /**
     * Prüfergebnis in einem Satz.
     *
     * `pruefen()` der Kette prüft Laufnummern, Verkettung **und** jeden Hash —
     * ein nachträglich geänderter Eintrag fällt hier auf. Ein leerer Prüfspruch
     * („in Ordnung“) ohne Einträge wäre irreführend, deshalb der eigene Fall.
     */
    fun integritaet(audit: Audit): String = try {
        audit.pruefen()
        when (val anzahl = audit.len()) {
            0u -> "Kette leer — noch kein Vorgang protokolliert."
            else -> String.format(
                Locale.GERMANY,
                "Kette in Ordnung: %d Einträge, letzter Hash %s",
                anzahl.toLong(),
                kurz(audit.letzterHash()),
            )
        }
    } catch (e: VciException) {
        "Kette gebrochen: ${e.message ?: e.toString()}"
    }

    /**
     * Der Nachweis als Text: Kopf, Prüfbefund, dann die Kette als JSON.
     *
     * Bewusst Text und kein PDF: der Bericht geht per Mail oder Ticket raus, und
     * JSON lässt sich beim Empfänger maschinell nachprüfen (`vci-check`).
     */
    fun export(audit: Audit, geraet: String, zeitpunkt: String): String = buildString {
        appendLine(KOPF)
        appendLine("Gerät: $geraet")
        appendLine("Zeitpunkt: $zeitpunkt")
        appendLine("Einträge: ${audit.len()}")
        appendLine("Prüfung: ${integritaet(audit)}")
        appendLine("Katalogstand: Inhalte aus content/funktionen (Freigabe je Inhalt im Feld provenance.freigabe)")
        appendLine()
        append(audit.alsJson())
    }
}
