package de.werkstatt.vci.transport

/**
 * Schnittstelle **oberhalb** der CAN-Rahmen: eine fertige Diagnosenachricht
 * rein, die Antwort raus.
 *
 * Klasse-1-Dongles (STN/ELM327-kompatibel) machen ISO-TP selbst — Segmentierung,
 * Flow-Control und Wiederzusammensetzung passieren im Dongle. Der Rust-Kern und
 * seine ISO-TP-Schicht sind auf diesem Weg nicht beteiligt. Deshalb steht diese
 * Schnittstelle neben `CanTransport` und nicht darunter; Begründung und Grenzen:
 * `docs/vci-dongle-matrix.md` §1.1.
 */
interface DiagnoseKanal {

    /**
     * Schickt eine Diagnosenachricht (z. B. `3E 00`) und liefert die
     * Antwortnutzlast ohne PCI- und Header-Bytes.
     */
    fun anfrage(nachricht: ByteArray): ByteArray

    fun schliesse()
}

/**
 * Byte-Strecke zum Dongle: Textbefehle raus, Zeilen rein.
 *
 * Bewusst so schmal: BLE-GATT-UART, klassisches Bluetooth-SPP und USB-CDC
 * liefern alle Textzeilen, unterscheiden sich aber im Transport. Was hier
 * dahinter liegt, ist Sache der Plattform und im Test ein Skript.
 */
interface SerielleStrecke {

    fun schreibe(text: String): Boolean

    /** Nächste Zeile ohne Zeilenende; `null`, wenn innerhalb der Frist nichts kam. */
    fun zeile(timeoutMs: Long): String?
}
