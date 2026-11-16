package de.werkstatt.vci

import java.util.Locale
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.CanTransport
import uniffi.vci_ffi.Funktion
import uniffi.vci_ffi.FunktionsKontextFfi
import uniffi.vci_ffi.VciException

/**
 * Was der Bediener am Fahrzeug bestätigt und was der Dongle gemessen hat.
 *
 * Die Bordspannung steht hier als **Messwert**, nicht als Häkchen: der Kern
 * verlangt für Funktionen mit Spannungsbedingung einen vom Transport
 * gemeldeten Wert. `null` heißt „nicht messbar“ und wird wie „nicht erfüllt“
 * behandelt — „unbekannt“ ist nicht „in Ordnung“.
 */
data class FahrzeugZustand(
    val vin: String = "",
    val klemme15: Boolean = false,
    val motorAus: Boolean = true,
    val vbatMv: UInt? = null,
)

/** Eine Vorbedingung, wie sie am Bildschirm steht: Wort, Zustand, Begründung. */
data class VorbedingungZeile(
    val titel: String,
    val erfuellt: Boolean,
    val text: String,
)

/**
 * Prüft die Vorbedingungen **vor** dem ersten Rahmen.
 *
 * Der Kern prüft sie noch einmal selbst (er muss, sonst wäre die Prüfung
 * umgehbar) — hier geht es darum, dass der Bediener die fehlende Bedingung
 * sieht, *bevor* er startet, und nicht als Fehlermeldung danach.
 */
object Vorabpruefung {

    /** mV als Volt mit Komma — 12480 → „12,48 V“. */
    fun volt(mv: UInt): String = String.format(Locale.GERMANY, "%.2f V", mv.toDouble() / 1000.0)

    fun zeilen(funktion: Funktion, zustand: FahrzeugZustand): List<VorbedingungZeile> =
        funktion.vorbedingungen().map { bedingung ->
            when (bedingung.art) {
                "klemme15" -> VorbedingungZeile(
                    titel = "Klemme 15",
                    erfuellt = zustand.klemme15,
                    text = if (zustand.klemme15) {
                        "liegt an (vom Bediener bestätigt)"
                    } else {
                        "liegt nicht an — Zündung einschalten und bestätigen"
                    },
                )

                "motor_aus" -> VorbedingungZeile(
                    titel = "Motor aus",
                    erfuellt = zustand.motorAus,
                    text = if (zustand.motorAus) {
                        "steht (vom Bediener bestätigt)"
                    } else {
                        "läuft noch — Motor abstellen, sonst schreibt der Ablauf in einen laufenden Betrieb"
                    },
                )

                "bordspannung" -> {
                    val soll = String.format(
                        Locale.GERMANY,
                        "%.1f–%.1f V",
                        bedingung.minMv.toDouble() / 1000.0,
                        bedingung.maxMv.toDouble() / 1000.0,
                    )
                    val mv = zustand.vbatMv
                    when {
                        mv == null -> VorbedingungZeile(
                            "Bordspannung",
                            false,
                            "nicht messbar — Dongle liefert keinen Wert, Soll $soll",
                        )

                        mv.toLong() < bedingung.minMv.toLong() -> VorbedingungZeile(
                            "Bordspannung",
                            false,
                            "${volt(mv)} — zu niedrig, Soll $soll (Batterie laden, Ladegerät anschließen)",
                        )

                        mv.toLong() > bedingung.maxMv.toLong() -> VorbedingungZeile(
                            "Bordspannung",
                            false,
                            "${volt(mv)} — zu hoch, Soll $soll (Ladegerät abklemmen)",
                        )

                        else -> VorbedingungZeile(
                            "Bordspannung",
                            true,
                            "${volt(mv)} (Soll $soll)",
                        )
                    }
                }

                // Kein Absturz bei einer Bedingung, die diese App-Version nicht
                // kennt: sie wird als offen gezeigt und hält den Ablauf an.
                else -> VorbedingungZeile(
                    funktion.titel(),
                    false,
                    "unbekannte Bedingung „${bedingung.art}“ — nicht prüfbar",
                )
            }
        }

    fun bereit(funktion: Funktion, zustand: FahrzeugZustand): Boolean =
        zeilen(funktion, zustand).all { it.erfuellt }
}

/** Ein Schritt mit dem, was gesendet wurde, was erwartet war und was geschah. */
data class SchrittZeile(
    val nummer: UInt,
    val anfrage: String,
    val erwartet: String,
    val ausgang: String,
    val ok: Boolean,
)

/** Ergebnis eines Ablaufs — gesperrt, gescheitert oder durchgelaufen. */
data class FunktionsLauf(
    val funktion: String,
    val schritte: List<SchrittZeile>,
    val backups: List<String>,
    val laufnummern: List<UInt>,
    val fehler: String?,
    val gesperrt: String?,
) {
    val erfolgreich: Boolean get() = gesperrt == null && fehler == null
}

/**
 * Führt eine Funktion aus und macht aus der Audit-Kette die Schrittliste.
 *
 * Warum die Schritte aus der Kette kommen und nicht aus einem Fortschritts-
 * Rückruf: der Kern meldet jeden Schritt dort ab, wo er hingehört — auch den
 * gescheiterten und den per NRC abgelehnten. Eine zweite Meldung über einen
 * Callback wäre eine zweite Wahrheit, die auseinanderlaufen kann. Der Preis ist
 * ehrlich benannt: die Liste entsteht **nach** dem Lauf, während des Laufs
 * steht „läuft …“ da.
 */
object FunktionsBediener {

    /** Was der Bediener sieht, solange ein Schritt nicht gesendet wurde. */
    const val NICHT_GESENDET = "nicht gesendet"

    /**
     * Grund, warum diese Funktion nicht starten darf — `null`, wenn sie darf.
     *
     * Gefragt wird der Kern (`pruefe_freigabe`), nicht die App: die vier
     * Katalogentwürfe ab R2 tragen `freigabe: unbelegt` und werden deshalb
     * angehalten, egal wer sie startet.
     */
    fun sperre(funktion: Funktion): String? = try {
        funktion.pruefeFreigabe()
        null
    } catch (e: VciException) {
        e.message ?: e.toString()
    }

    fun laufen(
        funktion: Funktion,
        transport: CanTransport,
        zustand: FahrzeugZustand,
        bestaetigung: Boolean,
        audit: Audit,
        zeitMs: ULong,
    ): FunktionsLauf {
        val sperre = sperre(funktion)
        if (sperre != null) {
            // Kein Rahmen hinaus, keine Zeile in der Kette — der Grund steht
            // am Bildschirm, und das ist der ganze Vorgang.
            return FunktionsLauf(
                funktion = funktion.id(),
                schritte = funktion.schrittliste().map {
                    SchrittZeile(it.nummer, it.anfrageHex, it.erwartetHex, "gesperrt", false)
                },
                backups = emptyList(),
                laufnummern = emptyList(),
                fehler = null,
                gesperrt = sperre,
            )
        }

        val vorher = audit.eintraege().size
        var ergebnis: uniffi.vci_ffi.AusfuehrungFfi? = null
        val fehler = try {
            ergebnis = funktion.ausfuehren(
                transport,
                FunktionsKontextFfi(
                    vin = zustand.vin,
                    klemme15 = zustand.klemme15,
                    motorAus = zustand.motorAus,
                    bestaetigung = bestaetigung,
                ),
                audit,
                zeitMs,
            )
            null
        } catch (e: VciException) {
            e.message ?: e.toString()
        }

        // Ein Eintrag je versuchtem Schritt; der Rest wurde nicht mehr gesendet.
        val neu = audit.eintraege().drop(vorher)
        val schritte = funktion.schrittliste().mapIndexed { i, schritt ->
            val eintrag = neu.getOrNull(i)
            SchrittZeile(
                nummer = schritt.nummer,
                anfrage = schritt.anfrageHex,
                erwartet = schritt.erwartetHex,
                ausgang = eintrag?.ergebnis ?: NICHT_GESENDET,
                ok = eintrag?.ergebnis?.startsWith("Erfolg") == true,
            )
        }

        return FunktionsLauf(
            funktion = funktion.id(),
            schritte = schritte,
            backups = ergebnis?.backupHex ?: emptyList(),
            laufnummern = ergebnis?.auditLaufnummern ?: neu.map { it.laufnummer },
            fehler = fehler,
            gesperrt = null,
        )
    }
}
