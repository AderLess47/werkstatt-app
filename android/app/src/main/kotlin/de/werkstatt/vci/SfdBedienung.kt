package de.werkstatt.vci

import uniffi.vci_ffi.VciException

/**
 * Die Rolle, die ein Fahrzeug für die SFD-Challenge erwartet.
 *
 * Welcher Wert gilt, steht in der Diagnosebeschreibung des Fahrzeugs — die drei
 * hier angebotenen Rollen sind die, die in der Halle vorkommen. Der Code geht
 * unverändert in `31 01 C0 04 <Rolle>`; die App rät nichts dazu.
 */
internal enum class SfdRolle(val code: Int, val anzeige: String, val hinweis: String) {
    EXTENDED(0x01, "Extended", "Regelrolle für die meisten Steuergeräte"),
    ROLLE_ZWEI(0x02, "Rolle 2", "zweite Rolle, je Fahrzeug andere Rechte"),
    ENTWICKLUNG(0x03, "Entwicklung", "nur wo die Diagnosebeschreibung sie nennt"),
    ;

    companion object {
        /** Rolle zu einem Byte aus einer Antwort — `null`, wenn unbekannt. */
        fun ausCode(code: Int): SfdRolle? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Prüfung des Token-Textes, bevor er über den Bus geht.
 *
 * Dieselben Regeln wie `aus_hex` im Rust-Kern (`core/src/sfd.rs`): Leerraum,
 * `:` und `-` sind Trenner, alles andere muss eine Hexziffer sein. Die Prüfung
 * hier ist **kein** Ersatz für die im Kern — sie existiert, damit der Knopf
 * „Entsperren“ schon vor dem Senden sagen kann, was nicht stimmt, statt eine
 * Ablehnung des Steuergeräts zu ernten.
 */
internal object SfdToken {

    /** Längster Token, der in eine ISO-TP-Nachricht passt: 0x0FFF − 4 Byte Kopf. */
    const val MAX_BYTES: Int = 0x0FFF - 4

    /**
     * Ergebnis der Prüfung.
     *
     * [normalisiert] ist der Text ohne Trenner in Großbuchstaben — genau das,
     * was an den Kern geht. [leer] unterscheidet „noch nichts eingegeben“ von
     * „etwas Falsches eingegeben“.
     */
    data class Pruefung(
        val normalisiert: String,
        val bytes: Int,
        val fehler: String?,
        val leer: Boolean,
    ) {
        val ok: Boolean get() = fehler == null && bytes > 0
    }

    fun pruefen(text: String): Pruefung {
        val ziffern = StringBuilder(text.length)
        text.forEachIndexed { stelle, zeichen ->
            if (zeichen.isWhitespace() || zeichen == ':' || zeichen == '-') {
                return@forEachIndexed
            }
            val ziffer = when (zeichen) {
                in '0'..'9' -> zeichen
                in 'a'..'f' -> zeichen.uppercaseChar()
                in 'A'..'F' -> zeichen
                else -> return Pruefung(
                    normalisiert = "",
                    bytes = 0,
                    fehler = "Zeichen „$zeichen“ an Stelle ${stelle + 1} ist keine Hexziffer",
                    leer = false,
                )
            }
            ziffern.append(ziffer)
        }

        if (ziffern.isEmpty()) {
            return Pruefung("", 0, fehler = null, leer = true)
        }
        if (ziffern.length % 2 != 0) {
            return Pruefung(
                normalisiert = ziffern.toString(),
                bytes = ziffern.length / 2,
                fehler = "ungerade Zahl von Ziffern (${ziffern.length}) — ein Halbbyte fehlt",
                leer = false,
            )
        }
        val bytes = ziffern.length / 2
        if (bytes > MAX_BYTES) {
            return Pruefung(
                normalisiert = ziffern.toString(),
                bytes = bytes,
                fehler = "$bytes Byte — höchstens $MAX_BYTES passen in eine Nachricht",
                leer = false,
            )
        }
        return Pruefung(ziffern.toString(), bytes, fehler = null, leer = false)
    }
}

/**
 * Deutung der Rohantwort auf `22 01 74`.
 *
 * **Vorläufig, und das steht auch auf dem Bildschirm:** welches Byte welchen
 * Zustand trägt, ist nicht Teil der hier verfügbaren Diagnosebeschreibung. Die
 * Deutung folgt der einfachsten Lesart — Byte 0: `00` gesperrt, `01` entsperrt;
 * Byte 1: aktive Rolle; Byte 2–3: Restlaufzeit in Minuten (Big Endian, `0000`
 * heißt „keine Angabe“). Alles, was nicht passt, wird **nicht** geraten: dann
 * steht „Zustand unbekannt“ da, und die Rohbytes bleiben sichtbar.
 *
 * Am Fahrzeug ist genau das zu prüfen (docs/geraetepruefung.md): ob die Deutung
 * stimmt, zeigt erst eine Antwort mit bekanntem Zustand.
 */
internal object SfdStatusDeutung {

    /**
     * @property gesperrt `true` gesperrt, `false` entsperrt, `null` nicht deutbar
     * @property rolle aktive Rolle als Byte-Wert, nur bei entsperrtem Zustand
     * @property restMinuten Restlaufzeit in Minuten; `null` = keine Angabe
     * @property roh Hex der Rohbytes — steht immer neben der Deutung
     */
    data class Deutung(
        val gesperrt: Boolean?,
        val rolle: Int?,
        val rolleText: String?,
        val restMinuten: Int?,
        val roh: String,
        val text: String,
    )

    fun hex(daten: ByteArray): String =
        daten.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun rolleText(rolle: Int): String =
        SfdRolle.ausCode(rolle)?.anzeige ?: "unbekannte Rolle"

    fun deute(daten: ByteArray): Deutung {
        val roh = hex(daten)
        if (daten.isEmpty()) {
            return Deutung(
                gesperrt = null,
                rolle = null,
                rolleText = null,
                restMinuten = null,
                roh = roh,
                text = "keine Statusbytes — das Steuergerät antwortet ohne Inhalt",
            )
        }

        val zustand = daten[0].toInt() and 0xFF
        val gesperrt = when (zustand) {
            0x00 -> true
            0x01 -> false
            else -> null
        }
        if (gesperrt == null) {
            return Deutung(
                gesperrt = null,
                rolle = null,
                rolleText = null,
                restMinuten = null,
                roh = roh,
                text = "Zustand unbekannt: Byte 0 ist 0x%02X".format(zustand),
            )
        }

        if (gesperrt) {
            return Deutung(true, null, null, null, roh, "gesperrt")
        }

        val rolle = daten.getOrNull(1)?.let { it.toInt() and 0xFF }?.takeIf { it != 0 }
        val rest = daten.takeIf { it.size >= 4 }?.let {
            ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF)
        }?.takeIf { it != 0 }

        val teile = mutableListOf("entsperrt")
        if (rolle != null) {
            teile += "Rolle 0x%02X (%s)".format(rolle, rolleText(rolle))
        } else {
            teile += "keine aktive Rolle genannt"
        }
        if (rest != null) {
            teile += "Restlaufzeit $rest min"
        }

        return Deutung(
            gesperrt = false,
            rolle = rolle,
            rolleText = rolle?.let(::rolleText),
            restMinuten = rest,
            roh = roh,
            text = teile.joinToString(" · "),
        )
    }
}

/**
 * Übersetzt, was aus dem Kern zurückkommt, in eine Zeile für die Werkstatt.
 *
 * Die Trennung ist der Zweck dieser Datei: eine **UDS-Ablehnung** (`7F …`, das
 * Steuergerät sagt nein), ein **Aufschub** (`0x78`, es rechnet noch), ein
 * **Formatfehler** (`VciError::Sfd`, der Token passt nicht), eine
 * **Zeitüberschreitung** und ein **USB-Abbruch** verlangen vier verschiedene
 * Reaktionen. „Fehler“ allein schickt den Techniker in die falsche Richtung.
 */
internal object SfdFehler {

    /** Art des Fehlers — steht als Wort vor dem Text, nicht nur als Farbe. */
    enum class Art(val anzeige: String) {
        NRC("UDS-Ablehnung"),
        AUFSCHUB("Aufschub 0x78"),
        FORMAT("Format/Token"),
        ZEITUEBERSCHREITUNG("Zeitüberschreitung"),
        USB("USB/Gerät"),
        PROTOKOLL("Protokoll"),
        UNERWARTET("unerwartet"),
    }

    data class Meldung(val art: Art, val text: String, val hinweis: String?)

    fun keinTransport(): Meldung = Meldung(
        Art.USB,
        "kein Dongle geöffnet",
        "Zuerst „USB-Dongle öffnen“ — ohne offenes Gerät geht keine Nachricht auf den Bus",
    )

    fun aus(e: Throwable): Meldung = when (e) {
        is VciException.Nrc -> nrc(e.dienst.toInt(), e.code.toInt())
        is VciException.Sfd -> Meldung(
            Art.FORMAT,
            "SFD-Nachricht passt nicht zum Ablauf: ${e.detail}",
            "Token aus dem Portal unverändert einfügen; die App kürzt oder rät nichts",
        )
        is VciException.Disconnected -> Meldung(
            Art.USB,
            "Gerät ist getrennt",
            "Kabel prüfen und den Dongle neu öffnen — danach Status erneut lesen",
        )
        is VciException.Timeout -> Meldung(
            Art.ZEITUEBERSCHREITUNG,
            "keine Antwort innerhalb der Frist",
            "Zündung an? Stimmt die Adressierung (0x7E0/0x7E8)? Bei 0x78 erneut prüfen",
        )
        is VciException.Io -> Meldung(
            Art.USB,
            "Transportfehler beim Senden oder Empfangen",
            "USB-Kabel und Stromversorgung prüfen, danach neu öffnen",
        )
        is VciException.Protocol -> Meldung(Art.PROTOKOLL, e.detail, null)
        else -> Meldung(
            Art.UNERWARTET,
            e.message ?: e.toString(),
            null,
        )
    }

    /** Ablehnung des Steuergeräts, mit Klartext je NRC. */
    fun nrc(dienst: Int, code: Int): Meldung {
        val kopf = "Dienst 0x%02X abgelehnt, NRC 0x%02X (%s)"
            .format(dienst, code, DienstAntwort.nrcText(code))
        val hinweis = when (code) {
            0x78 -> "Das Steuergerät rechnet weiter — nach kurzer Wartezeit erneut prüfen, " +
                "nicht sofort neu senden"
            0x35 -> "Token falsch — im Portal neu erzeugen; ein abgeschnittener Token wird " +
                "nicht gesendet"
            0x36 -> "zu viele Fehlversuche — Wartezeit des Steuergeräts abwarten, nicht wiederholen"
            0x33 -> "Sicherheitszugriff nötig — zuerst entsperren"
            0x31 -> "Rolle oder Kennung passt nicht zu diesem Steuergerät"
            0x22 -> "Bedingungen nicht erfüllt — Zündung an, Fahrzeug in Ruhe?"
            0x37 -> "Wartezeit nach Fehlversuchen noch nicht abgelaufen"
            0x7E, 0x7F -> "in der aktiven Sitzung nicht unterstützt"
            else -> null
        }
        val art = if (code == 0x78) Art.AUFSCHUB else Art.NRC
        return Meldung(art, kopf, hinweis)
    }
}

/**
 * Doppelklick-Schutz für laufende Busaufrufe.
 *
 * Ein zweiter Aufruf, während der erste noch auf Rahmen wartet, würde zwei
 * ISO-TP-Nachrichten ineinanderschieben — am Steuergerät kommt Unsinn an. Die
 * Sperre merkt sich, **was** läuft: der Text steht während des Wartens auf dem
 * Bildschirm und nach einem Abbruch bleibt er lesbar.
 *
 * Bewusst ohne Coroutinen und ohne Android: das Verhalten ist auf der JVM
 * prüfbar, und die Compose-Schicht muss nur noch fragen (`belegen`).
 */
internal class SfdSperre {

    var aktiv: String? = null
        private set

    /** `true`, wenn der Aufruf starten darf; `false` bei laufendem Aufruf. */
    fun belegen(was: String): Boolean {
        if (aktiv != null) return false
        aktiv = was
        return true
    }

    /** Gibt frei — mehrfaches Freigeben ist harmlos. */
    fun freigeben() {
        aktiv = null
    }
}
