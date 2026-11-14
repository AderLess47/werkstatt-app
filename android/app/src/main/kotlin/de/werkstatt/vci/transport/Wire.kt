package de.werkstatt.vci.transport

/**
 * Drahtformat des VCI, gespiegelt aus `j2534/src/transport_usb.c`.
 *
 * ```
 * 0xAA 0x55 | Typ (1) | Länge (1) | Nutzlast (0..255) | CRC16 (2, little endian)
 * ```
 *
 * Das CRC ist CRC-16/CCITT-FALSE (Polynom 0x1021, Startwert 0xFFFF) über
 * Typ + Länge + Nutzlast — dieselbe Rechnung wie in C, damit beide Seiten
 * dieselben Bytes erzeugen. `WireTest` vergleicht sie mit den Werten aus dem
 * C-Code.
 */
object Wire {

    const val SYNC0 = 0xAA
    const val SYNC1 = 0x55
    const val KOPF = 4
    const val MAX_NUTZLAST = 255
    const val PAD: Byte = 0xAA.toByte()

    const val T_CAN = 0x01
    const val T_VBATT = 0x02
    const val T_VERSION = 0x03
    const val T_ERROR = 0x7F

    fun crc16(bytes: ByteArray, ab: Int = 0, anzahl: Int = bytes.size - ab): Int {
        var crc = 0xFFFF
        for (i in ab until ab + anzahl) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    /** Baut einen vollständigen Rahmen. */
    fun rahmen(typ: Int, nutzlast: ByteArray): ByteArray {
        require(nutzlast.size <= MAX_NUTZLAST) { "Nutzlast zu groß: ${nutzlast.size}" }
        val out = ByteArray(KOPF + nutzlast.size + 2)
        out[0] = SYNC0.toByte()
        out[1] = SYNC1.toByte()
        out[2] = typ.toByte()
        out[3] = nutzlast.size.toByte()
        nutzlast.copyInto(out, KOPF)
        val crc = crc16(out, 2, 2 + nutzlast.size)
        out[KOPF + nutzlast.size] = (crc and 0xFF).toByte()
        out[KOPF + nutzlast.size + 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    /**
     * Nutzlast eines CAN-Rahmens: `[id u32 LE][dlc u8][8 Datenbytes]`.
     *
     * Nicht belegte Bytes werden mit 0xAA aufgefüllt — ein definierter Wert
     * statt Zufall, wie in C.
     */
    fun canNutzlast(id: UInt, daten: ByteArray, dlc: Int): ByteArray {
        require(dlc in 0..8) { "DLC außerhalb 0..8: $dlc" }
        require(daten.size >= dlc) { "weniger Daten als DLC" }
        val p = ByteArray(13)
        p[0] = (id and 0xFFu).toByte()
        p[1] = ((id shr 8) and 0xFFu).toByte()
        p[2] = ((id shr 16) and 0xFFu).toByte()
        p[3] = ((id shr 24) and 0xFFu).toByte()
        p[4] = dlc.toByte()
        for (i in 0 until 8) p[5 + i] = PAD
        daten.copyInto(p, 5, 0, dlc)
        return p
    }

    data class CanRahmen(val id: UInt, val daten: ByteArray, val dlc: Int)

    fun liesCan(nutzlast: ByteArray): CanRahmen? {
        if (nutzlast.size < 5) return null
        val id = (nutzlast[0].toUInt() and 0xFFu) or
            ((nutzlast[1].toUInt() and 0xFFu) shl 8) or
            ((nutzlast[2].toUInt() and 0xFFu) shl 16) or
            ((nutzlast[3].toUInt() and 0xFFu) shl 24)
        val dlc = (nutzlast[4].toInt() and 0xFF).coerceAtMost(8)
        if (nutzlast.size < 5 + 8) return null
        return CanRahmen(id, nutzlast.copyOfRange(5, 13), dlc)
    }

    /** Bordspannung in mV aus der Antwort auf T_VBATT (u32 LE). */
    fun liesVbatMv(nutzlast: ByteArray): UInt? {
        if (nutzlast.size < 4) return null
        return (nutzlast[0].toUInt() and 0xFFu) or
            ((nutzlast[1].toUInt() and 0xFFu) shl 8) or
            ((nutzlast[2].toUInt() and 0xFFu) shl 16) or
            ((nutzlast[3].toUInt() and 0xFFu) shl 24)
    }
}

/**
 * Rahmenleser für einen Bytestrom.
 *
 * Der Bulk-Endpunkt liefert beliebige Teilstücke: hier wird gepuffert, nach
 * `0xAA 0x55` resynchronisiert und ein Rahmen mit falschem CRC verworfen —
 * dieselbe Betriebsart wie `frame_read()` in C, inklusive der Zähler.
 */
class RahmenLeser(private val kapazitaet: Int = 4096) {

    data class Rahmen(val typ: Int, val nutzlast: ByteArray)

    private val puffer = ByteArray(kapazitaet)
    private var laenge = 0

    var resyncs = 0
        private set
    var crcFehler = 0
        private set

    fun schiebe(bytes: ByteArray, anzahl: Int = bytes.size) {
        require(anzahl <= bytes.size)
        var rest = anzahl
        var ab = 0
        while (rest > 0) {
            if (laenge == kapazitaet) {
                // Puffer voll ohne vollständigen Rahmen: Hälfte verwerfen.
                puffer.copyInto(puffer, 0, kapazitaet / 2, kapazitaet)
                laenge = kapazitaet / 2
                resyncs++
            }
            val n = minOf(rest, kapazitaet - laenge)
            bytes.copyInto(puffer, laenge, ab, ab + n)
            laenge += n
            ab += n
            rest -= n
        }
    }

    fun naechster(): Rahmen? {
        while (true) {
            var gefunden = -1
            var i = 0
            while (i + 1 < laenge) {
                if (puffer[i] == Wire.SYNC0.toByte() && puffer[i + 1] == Wire.SYNC1.toByte()) {
                    gefunden = i
                    break
                }
                i++
            }
            if (gefunden > 0) {
                verwerfe(gefunden)
                resyncs++
            } else if (gefunden < 0 && laenge > 1) {
                // Das letzte Byte kann der Anfang eines Sync-Paars sein.
                puffer[0] = puffer[laenge - 1]
                laenge = 1
                resyncs++
            }

            if (laenge < Wire.KOPF) return null

            val nutzlastLaenge = puffer[3].toInt() and 0xFF
            val gebraucht = Wire.KOPF + nutzlastLaenge + 2
            if (laenge < gebraucht) return null

            val crc = (puffer[gebraucht - 2].toInt() and 0xFF) or
                ((puffer[gebraucht - 1].toInt() and 0xFF) shl 8)
            if (Wire.crc16(puffer, 2, 2 + nutzlastLaenge) != crc) {
                // Rahmen verwerfen und weiter suchen.
                verwerfe(2)
                crcFehler++
                continue
            }

            val typ = puffer[2].toInt() and 0xFF
            val nutzlast = puffer.copyOfRange(Wire.KOPF, Wire.KOPF + nutzlastLaenge)
            verwerfe(gebraucht)
            return Rahmen(typ, nutzlast)
        }
    }

    private fun verwerfe(n: Int) {
        if (n <= 0) return
        val rest = laenge - n
        if (rest > 0) puffer.copyInto(puffer, 0, n, laenge)
        laenge = if (rest > 0) rest else 0
    }
}
