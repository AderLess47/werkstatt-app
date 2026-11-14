package de.werkstatt.vci

/**
 * Der JSON-Bericht des VCI-Checkers (`vci-check --json`), gelesen in der App.
 *
 * Der Vertrag steht in `docs/vci-checker.md` §3 und wird von
 * `j2534/src/checker.c` (`chk_to_json`) erzeugt:
 *
 * ```json
 * {"device":"sim-vci","firmware":"1.4","verdict":"ok","resyncs":0,"crc_errors":0,
 *  "checks":[{"id":"vbat","status":"ok","value":12480,"unit":"mV",
 *             "message":"Bordspannung 12.48 V","hint":""}]}
 * ```
 *
 * Die App **entscheidet nichts nach**: sie zeigt, was der Checker gemessen hat,
 * und stellt den Handlungshinweis nach vorn. Genau deshalb ist dieser Leser
 * streng bei der Form (fehlende Felder, unbekannter Status) und nachsichtig bei
 * allem, was er nicht kennt — unbekannte Felder werden ignoriert, damit ein
 * späterer Checker die App nicht bricht.
 *
 * Bewusst ohne `org.json`: die Klasse kennt kein Android und ist damit auf der
 * JVM gegen feste Eingaben prüfbar (wie `Wire` und `StnKanal`). Der Parser
 * daneben ist klein, weil der Vertrag klein ist — kein allgemeiner
 * JSON-Ersatz, sondern gerade so viel, wie `chk_to_json` schreiben kann.
 */
data class VciBericht(
    val geraet: String,
    val firmware: String,
    val ergebnis: VciStatus,
    val resyncs: Long,
    val crcFehler: Long,
    val pruefungen: List<VciPruefung>,
) {

    /** Prüfungen, bei denen die Werkstatt etwas tun soll — in Berichtsreihenfolge. */
    val handlungsbedarf: List<VciPruefung>
        get() = pruefungen.filter { it.status.istProblem }

    /** Ein Satz für den Kopf der Anzeige (Vorgabe aus `docs/vci-checker.md` §4). */
    val zusammenfassung: String
        get() {
            val bestanden = pruefungen.count { it.status == VciStatus.OK }
            val erste = handlungsbedarf.firstOrNull()?.hinweis?.takeIf { it.isNotBlank() }
            return when (ergebnis) {
                VciStatus.OK -> "VCI in Ordnung — $bestanden von ${pruefungen.size} Prüfungen bestanden"
                VciStatus.WARNUNG -> "VCI eingeschränkt" + (erste?.let { " — $it" } ?: "")
                VciStatus.FEHLER -> "VCI nicht in Ordnung" + (erste?.let { " — $it" } ?: "")
                VciStatus.UEBERSPRUNGEN -> "Prüfung nicht gelaufen" + (erste?.let { " — $it" } ?: "")
            }
        }

    /** Wird geworfen, wenn der Text kein Bericht dieses Vertrags ist. */
    class Fehler(message: String) : Exception(message)

    companion object {

        /**
         * Liest einen Bericht aus dem JSON-Text.
         *
         * Wirft [Fehler] mit Klartext, wenn der Text kein Bericht ist — die
         * Oberfläche zeigt diese Meldung an, statt abzustürzen.
         */
        fun ausJson(text: String): VciBericht {
            if (text.isBlank()) throw Fehler("Der Bericht ist leer.")
            val wurzel = try {
                JsonLeser(text).liesWert()
            } catch (e: IllegalArgumentException) {
                throw Fehler("Kein gültiges JSON: ${e.message}")
            }
            val obj = wurzel as? Map<*, *> ?: throw Fehler("Der Bericht muss ein JSON-Objekt sein.")

            val geraet = textFeld(obj, "device")
            val firmware = textFeld(obj, "firmware")
            val ergebnis = statusFeld(obj, "verdict")
            val resyncs = zahlFeld(obj, "resyncs")
            val crcFehler = zahlFeld(obj, "crc_errors")

            val roh = obj["checks"] as? List<*>
                ?: throw Fehler("Feld \"checks\" fehlt oder ist keine Liste.")
            val pruefungen = roh.mapIndexed { i, eintrag ->
                val p = eintrag as? Map<*, *>
                    ?: throw Fehler("Prüfung ${i + 1} in \"checks\" ist kein Objekt.")
                VciPruefung(
                    id = textFeld(p, "id"),
                    status = statusFeld(p, "status"),
                    wert = zahlFeld(p, "value"),
                    einheit = textFeld(p, "unit"),
                    meldung = textFeld(p, "message"),
                    hinweis = textFeld(p, "hint"),
                )
            }

            return VciBericht(geraet, firmware, ergebnis, resyncs, crcFehler, pruefungen)
        }

        private fun textFeld(obj: Map<*, *>, name: String): String =
            obj[name] as? String ?: throw Fehler("Feld \"$name\" fehlt oder ist kein Text.")

        private fun zahlFeld(obj: Map<*, *>, name: String): Long = when (val v = obj[name]) {
            is Long -> v
            is Double -> v.toLong()
            else -> throw Fehler("Feld \"$name\" fehlt oder ist keine Zahl.")
        }

        private fun statusFeld(obj: Map<*, *>, name: String): VciStatus {
            val text = textFeld(obj, name)
            return VciStatus.aus(text)
                ?: throw Fehler("Feld \"$name\" hat den unbekannten Wert \"$text\".")
        }
    }
}

/** Status einer Einzelprüfung, wie ihn der Checker als Text liefert. */
enum class VciStatus(val text: String) {

    OK("ok"),
    WARNUNG("warn"),
    FEHLER("fail"),
    UEBERSPRUNGEN("skip");

    /** Warnung und Fehler sind die Fälle, in denen die Werkstatt handeln soll. */
    val istProblem: Boolean get() = this == WARNUNG || this == FEHLER

    /** Kurzform für die Anzeige — Großbuchstaben, damit sie ohne Farbe lesbar bleibt. */
    val anzeige: String
        get() = when (this) {
            OK -> "OK"
            WARNUNG -> "HINWEIS"
            FEHLER -> "FEHLER"
            UEBERSPRUNGEN -> "ÜBERSPRUNGEN"
        }

    companion object {
        fun aus(text: String): VciStatus? {
            val t = text.trim().lowercase()
            return entries.firstOrNull { it.text == t }
        }
    }
}

/** Eine Zeile des Checker-Berichts. */
data class VciPruefung(
    val id: String,
    val status: VciStatus,
    val wert: Long,
    val einheit: String,
    val meldung: String,
    val hinweis: String,
) {

    /** Messwert mit Einheit, oder leer, wenn die Prüfung keinen hat. */
    val messwert: String
        get() = if (einheit.isBlank()) wert.toString() else "$wert $einheit"
}

/**
 * Kleiner JSON-Leser für genau diesen Vertrag.
 *
 * Kann Objekt, Liste, Text, Zahl, Wahrheitswert und `null` — nicht mehr. Alles,
 * was `chk_to_json` nicht schreiben kann, wird abgelehnt statt geraten. Die
 * Fehlermeldung nennt die Stelle, weil ein abgeschnittener Bericht (etwa beim
 * Kopieren aus einem Terminal) genau daran erkennbar ist.
 */
private class JsonLeser(private val text: String) {

    private var i = 0

    fun liesWert(): Any? {
        ueberspringeLeerraum()
        if (i >= text.length) fehler("unerwartetes Ende")
        return when (val c = text[i]) {
            '{' -> liesObjekt()
            '[' -> liesListe()
            '"' -> liesText()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) liesZahl() else fehler("unerwartetes Zeichen '$c'")
        }
    }

    private fun liesObjekt(): Map<String, Any?> {
        erwarte('{')
        val ergebnis = LinkedHashMap<String, Any?>()
        ueberspringeLeerraum()
        if (spitze() == '}') {
            i++
            return ergebnis
        }
        while (true) {
            ueberspringeLeerraum()
            val schluessel = liesText()
            ueberspringeLeerraum()
            erwarte(':')
            ergebnis[schluessel] = liesWert()
            ueberspringeLeerraum()
            when (spitze()) {
                ',' -> i++
                '}' -> {
                    i++
                    return ergebnis
                }
                else -> fehler("erwartet ',' oder '}'")
            }
        }
    }

    private fun liesListe(): List<Any?> {
        erwarte('[')
        val ergebnis = ArrayList<Any?>()
        ueberspringeLeerraum()
        if (spitze() == ']') {
            i++
            return ergebnis
        }
        while (true) {
            ergebnis += liesWert()
            ueberspringeLeerraum()
            when (spitze()) {
                ',' -> i++
                ']' -> {
                    i++
                    return ergebnis
                }
                else -> fehler("erwartet ',' oder ']'")
            }
        }
    }

    private fun liesText(): String {
        erwarte('"')
        val bau = StringBuilder()
        while (i < text.length) {
            when (val c = text[i++]) {
                '"' -> return bau.toString()
                '\\' -> {
                    if (i >= text.length) fehler("Abbruch nach '\\'")
                    when (val esc = text[i++]) {
                        '"' -> bau.append('"')
                        '\\' -> bau.append('\\')
                        '/' -> bau.append('/')
                        'b' -> bau.append('\b')
                        'f' -> bau.append('\u000C')
                        'n' -> bau.append('\n')
                        'r' -> bau.append('\r')
                        't' -> bau.append('\t')
                        'u' -> {
                            if (i + 4 > text.length) fehler("unvollständiges \\u")
                            val hex = text.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: fehler("kein Hex in \\u$hex")
                            bau.append(code.toChar())
                            i += 4
                        }
                        else -> fehler("unbekannte Escapesequenz '\\$esc'")
                    }
                }
                else -> bau.append(c)
            }
        }
        fehler("Text ohne schließendes Anführungszeichen")
    }

    private fun liesZahl(): Long {
        val start = i
        if (spitze() == '-') i++
        while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' || text[i] == 'E' || text[i] == '+' || text[i] == '-')) i++
        val roh = text.substring(start, i)
        if (roh.isEmpty() || roh == "-") fehler("keine Zahl")
        // Ganze Zahlen bleiben genau; ein Dezimalbruch (im Vertrag nicht
        // vorgesehen) wird abgeschnitten statt zu einem Absturz zu führen.
        return roh.toLongOrNull()
            ?: roh.toDoubleOrNull()?.toLong()
            ?: fehler("keine gültige Zahl: $roh")
    }

    private fun literal(wort: String, wert: Any?): Any? {
        if (!text.startsWith(wort, i)) fehler("erwartet '$wort'")
        i += wort.length
        return wert
    }

    private fun erwarte(c: Char) {
        ueberspringeLeerraum()
        if (spitze() != c) fehler("erwartet '$c'")
        i++
    }

    private fun spitze(): Char? = if (i < text.length) text[i] else null

    private fun ueberspringeLeerraum() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun fehler(grund: String): Nothing =
        throw IllegalArgumentException("$grund an Zeichen $i")
}
