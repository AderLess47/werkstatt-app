package de.werkstatt.vci

/**
 * P2* nach ISO 14229-2: 5 000 ms, die ein Steuergerät nach einer Negative
 * Response 0x78 („response pending“) für die endgültige Antwort beanspruchen
 * darf. Beide Wege warten mit dieser Frist erneut, statt die 0x78-Antwort als
 * Ergebnis zu melden.
 */
internal const val P2_STERN_MS = 5_000u

/**
 * Wie oft ein Steuergerät 0x78 schicken darf, bevor die App aufgibt.
 *
 * Drei Aufschübe sind 15 s zusätzlich zur ersten Frist — mehr als jede hier
 * gelesene Kennung braucht, aber endlich. Ohne Grenze könnte ein Steuergerät,
 * das dauerhaft 0x78 sendet, die App beliebig lange warten lassen.
 */
internal const val MAX_AUFSCHUB = 3

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

    /**
     * Aufschub, kein Ergebnis: `7F <Dienst> 78`.
     *
     * Das Steuergerät hat die Anfrage angenommen, rechnet aber noch (typisch bei
     * Routinen und beim Auslesen träger Speicher). Nach ISO 14229-1 darf es die
     * 0x78-Antwort wiederholen; die gültige Antwort kommt danach innerhalb von
     * [P2_STERN_MS] — ohne neue Anfrage. Wer 0x78 als Ablehnung anzeigt, meldet
     * einen Fehler, den es nicht gibt.
     */
    fun istAntwortNochOffen(bytes: ByteArray): Boolean =
        istAblehnung(bytes) && bytes.size >= 3 && (bytes[2].toInt() and 0xFF) == 0x78

    /** Klartext zu einem Negative Response Code — nur die, die in der Werkstatt vorkommen. */
    fun nrcText(nrc: Int): String = when (nrc) {
        0x10 -> "allgemein abgelehnt"
        0x11 -> "Dienst nicht unterstützt"
        0x12 -> "Unterfunktion nicht unterstützt"
        0x13 -> "falsche Länge"
        0x14 -> "Antwort zu lang"
        0x21 -> "gerade beschäftigt — gleich nochmal"
        0x22 -> "Bedingungen nicht erfüllt — Zündung an?"
        // 0x24–0x26 und 0x72 kommen bei Routinen (SFD) und beim Schreiben;
        // sie fehlten hier, solange nur lesende Dienste angezeigt wurden.
        0x24 -> "Reihenfolge verletzt — Schritt zuerst anfordern"
        0x25 -> "kein Antwortteilnehmer im Subnetz"
        0x26 -> "Fehler verhindert die Ausführung"
        0x31 -> "Wertebereich nicht unterstützt — falsche Kennung?"
        0x33 -> "Security Access nötig"
        0x35 -> "falscher Schlüssel — Token falsch"
        0x36 -> "zu viele Fehlversuche"
        0x37 -> "Wartezeit nach Fehlversuchen nicht abgelaufen"
        0x72 -> "Programmierfehler — Schreiben fehlgeschlagen"
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
