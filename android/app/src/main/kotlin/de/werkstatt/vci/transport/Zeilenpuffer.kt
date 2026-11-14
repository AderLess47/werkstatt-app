package de.werkstatt.vci.transport

/**
 * Zeilenbildung für Text-Dongles — gemeinsam für BLE und USB.
 *
 * Puffert ankommende Zeichen und gibt erst an `\r` oder `\n` eine Zeile heraus;
 * ein unfertiger Rest bleibt liegen und wird beim nächsten Nachschub
 * fortgesetzt. ELM-kompatible Geräte trennen mit Wagenrücklauf, manche schicken
 * zusätzlich `\n`, und USB-Wandler hängen je nach Treiber NUL-Bytes an — alles
 * drei wird hier abgeräumt.
 *
 * Bewusst nicht threadsicher: die BLE-Strecke hält ihre Sperre, die USB-Strecke
 * liest nur im Aufrufer-Thread.
 */
internal class Zeilenpuffer {

    private val text = StringBuilder()

    fun anhaengen(bytes: ByteArray, anzahl: Int = bytes.size) {
        if (anzahl <= 0) return
        // ISO-8859-1 statt UTF-8: die Antwort ist ASCII, aber ein einzelnes
        // 0x80 im Fehlerfall soll nicht zu U+FFFD werden und die Zeile verfälschen.
        text.append(String(bytes, 0, anzahl, Charsets.ISO_8859_1))
    }

    /** Nächste vollständige Zeile, oder `null`, wenn noch keine da ist. */
    fun naechste(): String? {
        val ende = text.indexOfFirst { it == '\r' || it == '\n' }
        if (ende < 0) return null
        val zeile = text.substring(0, ende).trim('\u0000')
        var weiter = ende
        while (weiter < text.length && (text[weiter] == '\r' || text[weiter] == '\n')) weiter++
        text.delete(0, weiter)
        return zeile
    }
}
