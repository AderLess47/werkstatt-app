package de.werkstatt.vci

import uniffi.vci_ffi.SchluesselAnbieter
import uniffi.vci_ffi.VciException

/**
 * Die Ebenen, die in der Halle vorkommen.
 *
 * `27` fragt den Seed mit der **ungeraden** Unterfunktion an, der Schlüssel geht
 * mit der geraden zurück (`27 01` fragen, `27 02` schicken). Welche Ebene ein
 * Steuergerät kennt, steht in seiner Diagnosebeschreibung; die drei hier sind
 * die üblichen, die freie Eingabe deckt den Rest ab.
 */
internal enum class SicherheitsEbene(val code: Int, val anzeige: String, val hinweis: String) {
    EINS(0x01, "Ebene 1", "die häufigste Ebene, etwa für Grundeinstellungen"),
    DREI(0x03, "Ebene 3", "je Fahrzeug andere Rechte — Diagnosebeschreibung prüfen"),
    FUENF(0x05, "Ebene 5", "höchste der üblichen Ebenen, oft für Schreibzugriffe"),
    ;

    companion object {
        fun ausCode(code: Int): SicherheitsEbene? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Prüfung einer Ebene aus freier Eingabe.
 *
 * Dieselbe Regel wie überall in dieser App: Hex, kein Dezimal. `01` und `1`
 * sind hier dasselbe Byte, `10` wäre dagegen 16 — deshalb wird ausschließlich
 * hexadezimal gelesen. Eine gerade Zahl wird abgelehnt: das ist die
 * Schlüssel-Unterfunktion, keine Ebene, und ein Senden dorthin würde am
 * Steuergerät als Längenfehler landen.
 */
internal object SicherheitEbenen {

    data class Pruefung(val wert: Int?, val fehler: String?) {
        val ok: Boolean get() = fehler == null && wert != null
    }

    fun pruefen(text: String): Pruefung {
        var s = text.trim()
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
        s = s.replace(" ", "").replace("_", "")

        if (s.isEmpty()) {
            return Pruefung(null, "leer — Ebene als Hex eingeben, z. B. 01")
        }
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return Pruefung(null, "„$s“ enthält ein Zeichen, das keine Hexziffer ist")
        }
        if (s.length > 2) {
            return Pruefung(null, "$s ist länger als ein Byte (höchstens 2 Ziffern)")
        }
        val wert = s.toInt(16)
        if (wert == 0) {
            return Pruefung(null, "Ebene 0x00 ist reserviert")
        }
        if (wert % 2 == 0) {
            return Pruefung(
                null,
                "0x%02X ist die Schlüssel-Unterfunktion, keine Ebene — Ebenen sind ungerade"
                    .format(wert),
            )
        }
        return Pruefung(wert, null)
    }
}

/**
 * Ebene, Seed und Ergebnis als Text — die drei Dinge, die der Abschnitt zeigt.
 *
 * Die Texte stehen hier und nicht im Composable, weil sie die eigentliche
 * Aussage tragen: „war bereits offen“ und „entsperrt“ sind verschiedene
 * Vorgänge, und ein Seed aus lauter Nullbytes ist keine Challenge, sondern die
 * Auskunft, dass nichts zu tun war. Auf der JVM sind sie prüfbar, am Bildschirm
 * wären sie es nicht.
 */
internal object SicherheitAnzeige {

    /** Hex mit Leerzeichen zwischen den Bytes — so, wie die Werkstatt liest. */
    fun hex(daten: ByteArray): String =
        daten.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun ebeneText(ebene: Int): String = "0x%02X".format(ebene)

    /**
     * Hex ohne Trenner zu Bytes — für den eingetragenen Schlüssel.
     *
     * Der Text ist vorher durch die Hex-Prüfung gegangen; hier wird nur noch
     * paarweise gelesen, damit niemand zwei verschiedene Umrechnungen pflegt.
     */
    fun bytes(normalisiert: String): ByteArray =
        ByteArray(normalisiert.length / 2) {
            normalisiert.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }

    /**
     * Der Seed, wie er auf dem Bus lag.
     *
     * Ein leerer Seed und einer aus lauter Nullbytes werden **benannt**, nicht
     * als Hex gezeigt: beide heißen nach ISO 14229-1 „Ebene war schon offen“,
     * und ein „00 00“ ohne diesen Satz würde als Challenge missverstanden.
     */
    fun seedText(seed: ByteArray?): String = when {
        seed == null -> "noch kein Seed gelesen"
        seed.isEmpty() -> "leer — das Steuergerät hat keinen Seed geliefert"
        seed.all { it == 0.toByte() } -> "${hex(seed)} — nur Nullbytes, also keine Challenge"
        else -> "${seed.size} Byte: ${hex(seed)}"
    }

    fun bereitsOffen(ebene: Int): String =
        "Ebene ${ebeneText(ebene)} war bereits offen — es wurde kein Schlüssel gesendet"

    fun entsperrt(ebene: Int, schluessel: ByteArray): String =
        "Ebene ${ebeneText(ebene)} entsperrt — Schlüssel mit ${schluessel.size} Byte " +
            "gesendet und bestätigt"

    fun keinSchluessel(ebene: Int): String =
        "Seed gelesen, aber kein Schlüssel vorhanden — es wurde nichts gesendet " +
            "(Ebene ${ebeneText(ebene)})"

    /**
     * Was im Fehlerfeld steht, wenn der Kern abbricht.
     *
     * Der leere Schlüssel ist ein Sonderfall: das Steuergerät hat geantwortet
     * und der Kern hat bewusst nichts geschickt. Das ist kein Busfehler, sondern
     * eine fehlende Eingabe — und der Hinweis soll dorthin zeigen, nicht auf
     * Kabel und Zündung.
     */
    fun meldung(ebene: Int, e: Throwable): SfdFehler.Meldung =
        if (e is VciException.Protocol && e.detail.contains("Schlüssel")) {
            SfdFehler.Meldung(
                SfdFehler.Art.PROTOKOLL,
                keinSchluessel(ebene),
                "Schlüssel zur Ebene als Hex eintragen — die App rechnet keinen aus",
            )
        } else {
            SfdFehler.aus(e)
        }
}

/**
 * Der Anbieter, den die App dem Kern reicht.
 *
 * Er rechnet **nichts**: er merkt sich Ebene und Seed, damit beides auf dem
 * Bildschirm steht, und gibt den Schlüssel zurück, den der Bediener eingetragen
 * hat. Ein Seed/Key-Algorithmus liegt bewusst nicht im Repository
 * ([docs/implementierungsplan.md] §3) — kommt der Schlüssel aus einem Portal
 * oder einem lizenzierten Baustein, wird er hier eingefügt und nicht erfunden.
 *
 * Ohne Schlüssel gibt er eine leere Antwort zurück; der Kern sendet dann nichts
 * und meldet den fehlenden Schlüssel, statt einen zu raten.
 */
internal class SicherheitsAnbieter(private val schluessel: ByteArray) : SchluesselAnbieter {

    var ebene: Int? = null
        private set

    var seed: ByteArray? = null
        private set

    override fun schluessel(ebene: UInt, seed: ByteArray): ByteArray {
        this.ebene = ebene.toInt()
        this.seed = seed.copyOf()
        return schluessel
    }
}
