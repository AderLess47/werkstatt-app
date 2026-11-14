package de.werkstatt.vci

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.werkstatt.vci.transport.FtdiStnVerbindung
import de.werkstatt.vci.transport.StnKanal
import de.werkstatt.vci.transport.StnKonfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Klasse-1-Weg am Kabel: OBDLink EX/SX über den FTDI-Wandler.
 *
 * Anders als der rohe USB-Weg laufen hier **Textbefehle**: der Dongle macht
 * ISO-TP selbst, die App sieht nur Zeilen. Deshalb zwei Schritte statt einer
 * Prüfung — erst öffnen (die `STI`-Kennung ist der Nachweis, dass wirklich
 * STN-Firmware antwortet), dann einen lesenden Dienst fahren.
 *
 * Was der Bildschirm **nicht** behauptet: dass ein durchgelassener Clone STN ist.
 * Die Kennung ist ein Filter, kein Beweis (siehe `docs/vci-dongle-matrix.md`).
 *
 * [onSchritt] meldet jeden angekündigten Schritt nach oben, damit er im
 * Selbsttest-Bericht landet.
 */
@Composable
internal fun ObdLinkAbschnitt(onSchritt: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verbindung = remember { FtdiStnVerbindung(context) }

    var kanal by remember { mutableStateOf<StnKanal?>(null) }
    var befunde by remember { mutableStateOf<List<Befund>>(emptyList()) }
    var meldung by remember { mutableStateOf<String?>(null) }

    /** Was gerade läuft — die Zeile bleibt beim Abbruch lesbar. */
    var schritt by remember { mutableStateOf<String?>(null) }
    val laeuft = schritt != null
    val konfiguration = remember { StnKonfiguration() }

    /** Zeigt den Schritt und meldet ihn nach oben — von dort geht er in den Bericht. */
    fun schrittZeigen(text: String) {
        schritt = text
        onSchritt(text)
    }

    DisposableEffect(verbindung) {
        onDispose {
            kanal?.schliesse()
            verbindung.close()
        }
    }

    fun oeffnen() {
        schrittZeigen("Suche FTDI-Gerät, frage die Freigabe ab, prüfe die STI-Kennung …")
        meldung = null
        befunde = emptyList()
        kanal?.schliesse()
        kanal = null
        scope.launch {
            when (val ergebnis = withContext(Dispatchers.IO) { verbindung.oeffnen() }) {
                is FtdiStnVerbindung.Ergebnis.Abgelehnt -> meldung = ergebnis.text
                is FtdiStnVerbindung.Ergebnis.Bereit -> {
                    // Verbindung steht und ist STN — jetzt erst die Einstellungen
                    // (Reset, Echo aus, Header, Protokoll) durchgeben.
                    schrittZeigen(
                        "Sende ATZ und die Einstellungen — Frist " +
                            fristText(konfiguration.startTimeoutMs) + " je Befehl"
                    )
                    val gestartet = withContext(Dispatchers.IO) {
                        runCatching { ergebnis.kanal.oeffnen() }
                    }
                    gestartet.fold(
                        onSuccess = {
                            kanal = ergebnis.kanal
                            befunde = listOf(
                                Befund(
                                    "OBDLink",
                                    "${ergebnis.kennung} · ${ergebnis.geraet}",
                                    ok = true
                                ),
                                Befund(
                                    "Dongle eingestellt",
                                    "Reset, Echo aus, Header an, 11-Bit-CAN bestätigt",
                                    ok = true
                                ),
                            )
                        },
                        onFailure = { e ->
                            ergebnis.kanal.schliesse()
                            meldung = "Dongle antwortet nicht: ${e.message ?: e.toString()}"
                        },
                    )
                }
            }
            schritt = null
        }
    }

    fun dienstLesen() {
        val offen = kanal ?: return
        meldung = null
        schrittZeigen(
            "Sende 22 F190 und warte auf die Antwort — Frist " +
                fristText(konfiguration.antwortTimeoutMs) +
                " für die ganze Antwort (der Dongle sammelt sie selbst)"
        )
        scope.launch {
            val antwort = withContext(Dispatchers.IO) {
                runCatching { offen.anfrage(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())) }
            }
            befunde = befunde + antwort.fold(
                onSuccess = { bytes ->
                    Befund(
                        "Dienst 22 F190",
                        DienstAntwort.beschreibe(bytes),
                        ok = !DienstAntwort.istAblehnung(bytes)
                    )
                },
                onFailure = { e ->
                    Befund(
                        "Dienst 22 F190",
                        "beim Warten auf die Antwort abgebrochen: " +
                            (e.message ?: e.toString()),
                        ok = false
                    )
                },
            )
            schritt = null
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("OBDLink (Klasse 1)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Textbefehle an Geräte mit STN-Firmware (OBDLink EX/SX). Der Dongle " +
            "macht ISO-TP selbst — die App liest nur Zeilen. Nur FTDI-Geräte, und nur " +
            "solche, deren STI-Kennung STN bestätigt.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = { oeffnen() },
            enabled = !laeuft
        ) {
            Text(if (kanal == null) "OBDLink öffnen" else "Neu öffnen")
        }
        Button(
            onClick = { dienstLesen() },
            enabled = !laeuft && kanal != null
        ) {
            Text("Dienst 22 F190 lesen")
        }
    }

    schritt?.let { text ->
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }

    meldung?.let { text ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }

    befunde.forEach { BefundZeile(it) }
}
