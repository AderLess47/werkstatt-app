package de.werkstatt.vci.transport

/**
 * Erkennt, ob am anderen Ende ein Gerät mit STN-Firmware hängt.
 *
 * Warum nicht `ATI`: STN-Geräte antworten dort bewusst mit einem
 * **ELM327-String** („ELM327 v1.4b") — laut STN-Handbuch entspricht er der
 * maximal vollständig unterstützten ELM-Version und lässt sich mit `STSATI`
 * sogar frei setzen. `ATI` unterscheidet also nichts.
 *
 * `STI` liefert dagegen die Firmware-Kennung im Format `STN<Geräte-ID> vX.Y.Z`,
 * zum Beispiel `STN2256 v5.9.1` oder `STN1101 v1.1.0`. ELM327-Clones kennen den
 * Befehl nicht und antworten mit `?`.
 *
 * **Das ist ein Filter, kein Beweis.** Ein Clone, der `STI` nachahmt, käme
 * durch. Deshalb bleibt es dabei: unterstützt werden nur die Geräte aus der
 * Dongle-Matrix, und ein durchgelassener Fremdling ist ein Supportfall, kein
 * Feature.
 *
 * Diese Klasse kennt kein Android und ist damit auf der JVM prüfbar.
 */
object StnKennung {

    /**
     * Firmware-Kennung: vierstellige Geräte-ID, dreiteilige Version.
     * Absichtlich streng — `STN2256` ohne Version oder `STN12 v1.0.0` zählen nicht.
     */
    private val MUSTER = Regex("""STN\d{4} v\d+\.\d+\.\d+""", RegexOption.IGNORE_CASE)

    private const val BEFEHL = "STI"
    private const val ANTWORT_TIMEOUT_MS = 1_500L

    /** Prüft eine einzelne Zeile auf die STN-Firmwareform (Groß-/Kleinschreibung egal). */
    fun istStn(text: String): Boolean = MUSTER.containsMatchIn(text)

    /**
     * Fragt `STI` und liefert die Kennung, wenn sie von STN-Firmware stammt.
     * `null` heißt: kein STN-Gerät, kein Text-Dongle oder keine Antwort.
     */
    fun erkenne(strecke: SerielleStrecke, timeoutMs: Long = ANTWORT_TIMEOUT_MS): String? {
        if (!strecke.schreibe(BEFEHL)) return null
        val frist = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val restMs = (frist - System.nanoTime()) / 1_000_000L
            if (restMs <= 0L) return null
            val zeile = strecke.zeile(restMs) ?: return null
            val text = zeile.trim()
            if (text == ">") return null
            // Echo (falls ATE1 noch an ist) und Leerzeilen überspringen.
            if (text.isEmpty() || text.equals(BEFEHL, true)) continue
            // "?" ist die Antwort eines ELM-Clones auf einen ST-Befehl.
            if (text == "?") return null
            if (istStn(text)) return text
        }
    }
}
