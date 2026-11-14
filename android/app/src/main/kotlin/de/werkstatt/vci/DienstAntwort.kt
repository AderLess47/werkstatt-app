package de.werkstatt.vci

/**
 * Lesbare Fassung einer Diagnoseantwort vom Klasse-1-Weg.
 *
 * Auf diesem Weg sieht die App nur Bytes, die der Dongle zusammengesetzt hat —
 * `62 F1 90 57 44 42 …` ist eine positive Antwort auf `22 F190` (Fahrgestell-
 * nummer), `7F 22 31` heißt: abgelehnt, Wertebereich nicht unterstützt. Beides
 * muss auf dem Bildschirm stehen, ohne dass jemand Hex lesen können muss.
 *
 * Bewusst ohne Android und ohne den Kanal: Bytes rein, Text raus. Damit ist die
 * Auswertung auf der JVM prüfbar, auch wenn das Gerät fehlt.
 */
object DienstAntwort {

    /** `62 F1 90` — Bytes mit Leerzeichen, Großbuchstaben. */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * Druckbare Zeichen aus der Antwort.
     *
     * Grob absichtlich: bei `22 F190` steht die Fahrgestellnummer als ASCII
     * hinter dem Kopf, bei Messwerten ist oft gar kein Text dabei. Was nicht
     * druckbar ist, fällt weg — der Hex-Teil daneben bleibt vollständig.
     */
    fun ascii(bytes: ByteArray): String =
        bytes.map { it.toInt() and 0xFF }
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")

    /** Negative Antwort nach ISO 14229: `7F <Dienst> <NRC>`. */
    fun istAblehnung(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes[0] == 0x7F.toByte()

    /** Klartext zu einem Negative Response Code — nur die, die in der Werkstatt vorkommen. */
    fun nrcText(nrc: Int): String = when (nrc) {
        0x10 -> "allgemein abgelehnt"
        0x11 -> "Dienst nicht unterstützt"
        0x12 -> "Unterfunktion nicht unterstützt"
        0x13 -> "falsche Länge"
        0x21 -> "gerade beschäftigt — gleich nochmal"
        0x22 -> "Bedingungen nicht erfüllt — Zündung an?"
        0x31 -> "Wertebereich nicht unterstützt — falsche Kennung?"
        0x33 -> "Security Access nötig"
        0x78 -> "Antwort kommt noch"
        0x7E, 0x7F -> "in dieser Sitzung nicht unterstützt"
        else -> "unbekannter Code"
    }

    /**
     * Ein Satz für die Anzeige: Größe, Hex und — wenn vorhanden — der Text.
     * Bei einer Ablehnung steht der Grund vorn, nicht die Bytefolge.
     */
    fun beschreibe(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "leere Antwort"
        if (istAblehnung(bytes)) {
            val dienst = if (bytes.size >= 2) "%02X".format(bytes[1]) else "?"
            if (bytes.size < 3) return "abgelehnt (Dienst $dienst, ohne Grund)"
            val nrc = bytes[2].toInt() and 0xFF
            return "abgelehnt (Dienst $dienst, NRC %02X: %s)".format(nrc, nrcText(nrc))
        }
        val text = ascii(bytes)
        return "${bytes.size} Byte · ${hex(bytes)}" + if (text.isEmpty()) "" else " · Text: $text"
    }
}
