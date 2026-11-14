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
import de.werkstatt.vci.transport.UsbCanTransport
import de.werkstatt.vci.transport.UsbDongleVerbindung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vci_ffi.IsoTp

/**
 * Roher USB-Weg (Weg A): eigenes Drahtformat, ISO-TP rechnet der Kern.
 *
 * Abgrenzung zum Knopf „USB prüfen“ weiter oben: der macht den Schnelltest
 * (Gerät finden, Bordspannung, TesterPresent) und schreibt sein Ergebnis in den
 * Selbsttest-Bericht. Hier wird zusätzlich **ein Dienst gelesen** — dieselbe
 * Kennung wie auf dem Klasse-1-Weg, damit sich beide Wege vergleichen lassen.
 *
 * Auf diesem Weg segmentiert der Rust-Kern (`isotp.rs`), der Dongle sieht nur
 * CAN-Rahmen. Der Bildschirm behauptet deshalb auch nichts über die Firmware
 * des Geräts: er kennt nur VID/PID und was auf dem Bus zurückkommt.
 */
@Composable
internal fun UsbAbschnitt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verbindung = remember { UsbDongleVerbindung(context) }

    var transport by remember { mutableStateOf<UsbCanTransport?>(null) }
    var befunde by remember { mutableStateOf<List<Befund>>(emptyList()) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var laeuft by remember { mutableStateOf(false) }

    DisposableEffect(verbindung) {
        onDispose { verbindung.close() }
    }

    fun oeffnen() {
        laeuft = true
        meldung = null
        befunde = emptyList()
        transport = null
        scope.launch {
            when (val ergebnis = withContext(Dispatchers.IO) { verbindung.oeffnen() }) {
                is UsbDongleVerbindung.Ergebnis.Abgelehnt -> meldung = ergebnis.text
                is UsbDongleVerbindung.Ergebnis.Bereit -> {
                    transport = ergebnis.transport
                    befunde = listOf(
                        Befund("USB-Dongle", ergebnis.kennung, ok = true),
                        Befund(
                            "Drahtformat",
                            "0xAA 0x55 · CRC-16 · ISO-TP im Kern bestätigt",
                            ok = true
                        ),
                    )
                }
            }
            laeuft = false
        }
    }

    fun dienstLesen() {
        val offen = transport ?: return
        laeuft = true
        meldung = null
        scope.launch {
            val antwort = withContext(Dispatchers.IO) {
                runCatching {
                    val iso = IsoTp()
                    iso.send(offen, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
                    iso.recv(offen, 1_500u)
                }
            }
            befunde = befunde + antwort.fold(
                onSuccess = { bytes ->
                    Befund(
                        "Dienst 22 F190",
                        DienstAntwort.beschreibe(bytes),
                        ok = !DienstAntwort.istAblehnung(bytes)
                    )
                },
                onFailure = { e -> Befund("Dienst 22 F190", e.message ?: e.toString(), ok = false) },
            )
            laeuft = false
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Eigenes Drahtformat (Weg A)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Dongles, die unser Rahmenformat sprechen (0xAA 0x55, CRC-16, CAN). " +
            "Der Kern macht hier ISO-TP selbst — der Dongle sieht nur Rahmen. " +
            "„USB prüfen“ oben ist der Schnelltest; hier wird ein Dienst gelesen.",
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
            Text(if (transport == null) "USB-Dongle öffnen" else "Neu öffnen")
        }
        Button(
            onClick = { dienstLesen() },
            enabled = !laeuft && transport != null
        ) {
            Text("Dienst 22 F190 lesen")
        }
    }

    if (laeuft) {
        Spacer(Modifier.height(8.dp))
        Text("Läuft …", style = MaterialTheme.typography.bodyLarge)
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
