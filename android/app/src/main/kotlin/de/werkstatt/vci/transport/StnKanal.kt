package de.werkstatt.vci.transport

/**
 * Fehler des STN-Wegs.
 *
 * Eigene Typen statt `VciException`: dieser Kanal liegt oberhalb der CAN-Rahmen,
 * der Rust-Kern ist hier nicht beteiligt — es gibt also auch keine
 * Kernexception zu melden.
 */
open class StnFehler(message: String) : Exception(message) {

    class KeineAntwort(message: String = "Steuergerät antwortet nicht") : StnFehler(message)

    class BefehlUnbekannt(befehl: String) : StnFehler("Dongle kennt den Befehl nicht: $befehl")

    class Zeitueberschreitung(message: String) : StnFehler(message)

    class Uebertragung(message: String) : StnFehler(message)
}

/**
 * Voreinstellungen für 11-Bit-CAN mit 500 kbit/s (ISO 15765-4, Motor-SG).
 *
 * `ATSP6` ist der Protokollbefehl, den STN-Geräte wie OBDLink verstehen; sie
 * akzeptieren ihn als ELM-kompatiblen Befehl. Andere Fahrzeuge brauchen andere
 * Werte — deshalb steht das hier als Datenklasse und nicht als Konstante.
 *
 * **Beide Wartezeiten sind Obergrenzen, nach denen *wir* aufgeben** — keine
 * Protokollfristen. Auf diesem Weg segmentiert der Dongle selbst; die Fristen
 * begrenzen nur, wie lange die App auf Textzeilen wartet.
 *
 * - [antwortTimeoutMs] (3 000 ms): Der Adapter entscheidet mit seinem eigenen
 *   Timer, wie lange er auf das Steuergerät wartet (`ATST`; Werkseinstellung
 *   `ST = 32`, rund 200 ms laut ELM327-Datenblatt), und antwortet danach
 *   `NO DATA`. Unsere Frist muss nur diesen Entscheidungsweg samt
 *   Übertragung überschreiten: eine Antwort über drei Rahmen braucht auf
 *   500 kbit/s weniger als eine Millisekunde Buszeit, der Rest ist Adapter-
 *   und USB-Zeit. 3 000 ms sind deshalb reichlich Reserve — auch für einen
 *   langsamen Clone, bewusst großzügiger als die 1 500 ms je Rahmen auf dem
 *   Kern-Weg.
 * - [startTimeoutMs] (2 000 ms): gilt für `ATZ` und die `AT`-Einstellungen.
 *   `ATZ` ist der langsamste Befehl (Reset), alles danach antwortet sofort mit
 *   `OK`; 2 000 ms decken den Reset mit Reserve ab.
 * - [p2SternTimeoutMs] (5 000 ms): gilt, wenn das Steuergerät mit einer
 *   Negative Response 0x78 („response pending“) um Aufschub bittet. ISO 14229-2
 *   nennt 5 000 ms bis zur endgültigen Antwort; danach wird **weiter gelesen**,
 *   nicht neu gefragt — nach 0x78 kommt die Antwort ohne neue Anfrage.
 *   Höchstens [maxAufschub] Mal, dann steht ein Fehler statt einer endlosen
 *   Wartezeile.
 *
 * **Grenze bei 0x78:** der Dongle entscheidet mit `ATST`, wie lange er nach
 * einer Antwort überhaupt zuhört (Werkseinstellung rund 200 ms, siehe oben).
 * Kommt die endgültige Antwort später, hat er das Zuhören schon beendet und
 * meldet `NO DATA` — dann steht hier eine Zeitüberschreitung, und die Ursache
 * ist die Einstellung des Adapters, nicht das Steuergerät. Für längere
 * Aufschübe muss `ATST` im Dongle hochgesetzt werden; die App setzt es bewusst
 * nicht, weil sie sonst auch jeden echten Ausfall 5 s lang aussitzt.
 */
data class StnKonfiguration(
    val anfrageHeader: Int = 0x7E0,
    val antwortHeader: Int = 0x7E8,
    val protokoll: String = "ATSP6",
    val startTimeoutMs: Long = 2_000,
    val antwortTimeoutMs: Long = 3_000,
    val p2SternTimeoutMs: Long = 5_000,
    val maxAufschub: Int = 3,
)

/**
 * Klasse-1-Transport: Textbefehle an ein STN-Gerät, Antwort als Diagnosenachricht.
 *
 * Der Ablauf ist der eines jeden ELM-kompatiblen Adapters:
 *
 * ```
 * ATZ  → Kennung         (Reset)
 * ATE0 → OK              (Echo aus: sonst steht der Befehl in der Antwort)
 * ATL0 → OK              (kein Zeilenvorschub)
 * ATS0 → OK              (keine Leerzeichen in Hex-Zeilen)
 * ATH1 → OK              (Header mit ausgeben — sonst ist die Antwort nicht zuzuordnen)
 * ATSP6 → OK             (Protokoll)
 * ATSH7E0 → OK           (Ziel-Header, nur bei Wechsel)
 * <Hex>  → 7E8 <PCI> <Daten…>
 * ```
 *
 * Das ISO-TP im Dongle ist der Grund, warum hier *keine* Segmentierung
 * stattfindet: ein Single Frame kommt als `7E8 02 7E 00`, eine lange Antwort
 * als Folge aus `10 …`, `21 …`, `22 …`. [StnAntwort] setzt sie zusammen.
 *
 * **0x78 wird abgewartet, nicht gemeldet:** schickt das Steuergerät
 * `7F <Dienst> 78`, fliegt die Zeile raus und es wird bis
 * [StnKonfiguration.p2SternTimeoutMs] weiter gelesen — ohne neue Anfrage, weil
 * ein zweites Senden eine zweite Routine starten würde.
 *
 * **Grenzen** (siehe Matrix §1.1): Lesen und R1-Service; kein R3, kein Flashen,
 * kein Security Access. Textbefehle sind langsam, große Transfers dauern
 * Sekunden.
 *
 * Diese Klasse kennt kein Android — sie spricht nur [SerielleStrecke] und ist
 * damit auf der JVM gegen aufgezeichnete Antworten prüfbar.
 */
class StnKanal(
    private val strecke: SerielleStrecke,
    private val konfiguration: StnKonfiguration = StnKonfiguration(),
) : DiagnoseKanal {

    private var offen = false
    private var header = -1

    /** Initialisiert den Dongle. Wirft [StnFehler], wenn er nicht mitspielt. */
    fun oeffnen() {
        val kennung = befehl("ATZ", konfiguration.startTimeoutMs)
        if (kennung.isEmpty()) throw StnFehler.Zeitueberschreitung("keine Antwort auf ATZ")
        for (b in listOf("ATE0", "ATL0", "ATS0", "ATH1")) erwarteOk(b)
        if (konfiguration.protokoll.isNotBlank()) erwarteOk(konfiguration.protokoll)
        // Nach ATZ steht der Header auf dem Dongle-Standard; wir wissen nichts
        // über ihn und setzen ihn beim ersten Zugriff neu.
        header = -1
        offen = true
    }

    override fun anfrage(nachricht: ByteArray): ByteArray {
        if (!offen) throw StnFehler.Uebertragung("Kanal ist nicht geöffnet")
        if (nachricht.isEmpty()) throw StnFehler.Uebertragung("leere Anfrage")
        setzeHeader(konfiguration.anfrageHeader)
        val hex = nachricht.alsHex()
        var zeilen = befehl(hex, konfiguration.antwortTimeoutMs)
        var aufschuebe = 0
        while (true) {
            pruefeFehler(zeilen, hex)
            // 0x78-Zeilen sind kein Ergebnis, sondern ein „warte noch“: sie
            // fliegen raus, und was übrig bleibt, wird zusammengesetzt.
            val inhalt = StnAntwort.ohneAufschub(zeilen, konfiguration.antwortHeader)
            if (inhalt.isNotEmpty()) {
                return StnAntwort.ausRahmen(inhalt, konfiguration.antwortHeader)
            }
            if (aufschuebe >= konfiguration.maxAufschub) {
                throw StnFehler.Zeitueberschreitung(
                    "Steuergerät hat ${konfiguration.maxAufschub}-mal 0x78 geschickt, " +
                        "aber keine Antwort geliefert"
                )
            }
            aufschuebe++
            // Nach 0x78 kommt die Antwort ohne neue Anfrage — deshalb nur weiter
            // lesen, nicht erneut senden. Ein zweites Senden würde eine zweite
            // Routine starten.
            zeilen = sammle(konfiguration.p2SternTimeoutMs)
            if (zeilen.isEmpty()) {
                throw StnFehler.Zeitueberschreitung(
                    "nach 0x78 keine Antwort innerhalb von " +
                        "${konfiguration.p2SternTimeoutMs} ms (Dongle-Timeout ATST?)"
                )
            }
        }
    }

    override fun schliesse() {
        offen = false
        header = -1
    }

    private fun setzeHeader(id: Int) {
        if (header == id) return
        erwarteOk("ATSH" + "%03X".format(id))
        header = id
    }

    private fun erwarteOk(befehl: String) {
        val zeilen = befehl(befehl, konfiguration.startTimeoutMs)
        pruefeFehler(zeilen, befehl)
        if (zeilen.none { it.equals("OK", true) }) {
            throw StnFehler.Uebertragung("$befehl nicht bestätigt: ${zeilen.joinToString(" | ")}")
        }
    }

    private fun pruefeFehler(zeilen: List<String>, kontext: String) {
        for (z in zeilen) {
            if (z == "?") throw StnFehler.BefehlUnbekannt(kontext)
            if (z.startsWith("NO DATA", true)) throw StnFehler.KeineAntwort()
            if (z.startsWith("UNABLE TO CONNECT", true) ||
                z.startsWith("CAN ERROR", true) ||
                z.startsWith("BUFFER FULL", true) ||
                z.startsWith("STOPPED", true)
            ) {
                throw StnFehler.Uebertragung(z)
            }
        }
    }

    /**
     * Schickt einen Befehl und sammelt Zeilen, bis der Dongle fertig ist:
     * Prompt `>`, `OK`, `?`, `NO DATA` oder Fristablauf.
     */
    private fun befehl(text: String, timeoutMs: Long): List<String> {
        if (!strecke.schreibe(text)) throw StnFehler.Uebertragung("Schreiben fehlgeschlagen: $text")
        val zeilen = sammle(timeoutMs)
        if (zeilen.isEmpty()) throw StnFehler.Zeitueberschreitung("keine Antwort auf $text")
        return zeilen
    }

    /** Liest Zeilen bis zum Prompt, einem Abschlusswort oder Fristablauf — ohne zu senden. */
    private fun sammle(timeoutMs: Long): List<String> {
        val zeilen = mutableListOf<String>()
        val frist = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val rest = (frist - System.nanoTime()) / 1_000_000L
            if (rest <= 0L) break
            val gelesen = strecke.zeile(rest) ?: break
            val z = gelesen.trim()
            if (z == ">") break
            if (z.isEmpty()) continue
            zeilen += z
            if (z == "?") break
            if (z.equals("OK", true)) break
            if (z.startsWith("NO DATA", true)) break
        }
        return zeilen
    }
}

/**
 * Setzt aus den Textzeilen des Dongles die Diagnosenachricht zusammen.
 *
 * Mit `ATH1` liefert der Adapter je Zeile einen CAN-Rahmen mit Header
 * (`7E8 02 7E 00`). Was danach kommt, ist ISO-TP:
 *
 * - `0x0N` — Single Frame, N Nutzbytes
 * - `0x1N` + Längenbyte — First Frame einer längeren Antwort
 * - `0x2N` — Folgerahmen mit Sequenznummer N
 *
 * Negative Antworten (`7F <Dienst> <NRC>`) sind Single Frames und werden
 * unverändert durchgereicht — was daraus wird, entscheidet der Aufrufer.
 */
object StnAntwort {

    fun ausRahmen(zeilen: List<String>, antwortHeader: Int): ByteArray {
        val rahmen = rahmenBytes(zeilen, antwortHeader)
        if (rahmen.isEmpty()) {
            throw StnFehler.KeineAntwort("keine Zeile mit Header " + "%03X".format(antwortHeader))
        }
        val erster = rahmen.first()
        return when (erster[0] shr 4) {
            0x0 -> einfachRahmen(erster)
            0x1 -> mehrteilig(rahmen)
            else -> throw StnFehler.Uebertragung(
                "unerwartetes PCI-Byte " + "%02X".format(erster[0])
            )
        }
    }

    /**
     * Entfernt die 0x78-Zeilen („Antwort kommt noch“) aus einer Zeilenfolge.
     *
     * So bleibt von einem Aufschub nichts übrig, was wie eine Ablehnung
     * aussieht: kommt danach die echte Antwort, steht sie allein da und wird
     * zusammengesetzt. Bleibt nichts übrig, war es nur ein Aufschub — dann
     * entscheidet der Aufrufer, ob er weiter wartet.
     */
    fun ohneAufschub(zeilen: List<String>, antwortHeader: Int): List<String> =
        zeilen.filterNot { zeile ->
            val rahmen = rahmenBytes(listOf(zeile), antwortHeader).firstOrNull()
            // `7E8 03 7F 22 78`: Byte 0 ist das PCI-Byte, erst danach beginnt die
            // Nutzlast — 0x7F steht also an Position 1, der NRC an Position 3.
            rahmen != null && rahmen.size >= 4 && (rahmen[0] shr 4) == 0x0 &&
                rahmen[1] == 0x7F && rahmen[3] == 0x78
        }

    /** Zerlegt die Zeilen in Byte-Listen und wirft fremde Header weg. */
    private fun rahmenBytes(zeilen: List<String>, antwortHeader: Int): List<List<Int>> =
        zeilen.mapNotNull { zeile ->
            val text = zeile.replace(" ", "").uppercase()
            val kopf = text.take(3).toIntOrNull(16) ?: return@mapNotNull null
            if (kopf != antwortHeader) return@mapNotNull null
            val bytes = text.drop(3).chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (bytes.isEmpty()) null else bytes
        }

    private fun einfachRahmen(rahmen: List<Int>): ByteArray {
        val laenge = rahmen[0] and 0x0F
        if (laenge == 0) throw StnFehler.Uebertragung("leere Antwort")
        if (rahmen.size < 1 + laenge) {
            throw StnFehler.Uebertragung("Rahmen zu kurz: ${rahmen.size} Byte für $laenge")
        }
        return ByteArray(laenge) { rahmen[1 + it].toByte() }
    }

    private fun mehrteilig(rahmen: List<List<Int>>): ByteArray {
        val kopf = rahmen.first()
        if (kopf.size < 2) throw StnFehler.Uebertragung("First Frame ohne Längenbyte")
        val laenge = ((kopf[0] and 0x0F) shl 8) or kopf[1]
        if (laenge <= 7) {
            // ISO 15765-2: bis 7 Byte gehört die Antwort in einen Single Frame.
            throw StnFehler.Uebertragung("First Frame mit Länge $laenge")
        }
        val nutz = ArrayList<Int>(laenge)
        nutz.addAll(kopf.drop(2))
        var erwartet = 1
        for (f in rahmen.drop(1)) {
            if (nutz.size >= laenge) break
            if (f.isEmpty()) continue
            val folge = f[0] and 0x0F
            if (folge != erwartet % 16) {
                throw StnFehler.Uebertragung("Folgerahmen $folge statt ${erwartet % 16}")
            }
            nutz.addAll(f.drop(1))
            erwartet++
        }
        if (nutz.size < laenge) {
            throw StnFehler.Uebertragung("Antwort unvollständig: ${nutz.size} von $laenge Byte")
        }
        return ByteArray(laenge) { nutz[it].toByte() }
    }
}

/** Hex ohne Trenner — `ATS0` schaltet die Leerzeichen in der Antwort ab. */
internal fun ByteArray.alsHex(): String = joinToString("") { "%02X".format(it) }
