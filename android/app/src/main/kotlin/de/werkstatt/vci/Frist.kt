package de.werkstatt.vci

import java.util.Locale

/**
 * Sekundenangabe für die Wartezeile — deutsch formatiert (`1,5 s`), damit die
 * Anzeige zur Werkstatt-Anleitung passt und nicht `1.5` schreibt.
 *
 * Steht bewusst in einer eigenen Datei ohne Compose: die Formatierung ist damit
 * auf der JVM prüfbar, obwohl die Wege selbst nur auf einem Gerät laufen. Die
 * Zahlen, die hier hereinkommen, stehen in `UsbAbschnitt` (1 500 ms je Rahmen),
 * `ObdLinkAbschnitt` (3 000 / 2 000 ms) und `DienstAntwort` (`P2_STERN_MS`) —
 * die Anzeige und die Frist sind damit derselbe Wert, nicht zwei Abschriften.
 */
internal fun fristText(ms: Long): String =
    String.format(Locale.GERMANY, "%.1f s", ms.toDouble() / 1_000.0)
