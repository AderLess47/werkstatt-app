package de.werkstatt.vci

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.werkstatt.vci.transport.BleGattVerbindung
import de.werkstatt.vci.transport.UsbDongleVerbindung
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Erster Bildschirm der App: Ist der Kern arbeitsfähig, und ist ein Dongle da?
 *
 * Zwei Stufen, bewusst getrennt: der Kern (lädt die Bibliothek, rechnet ISO-TP)
 * und der Dongle (USB-Host, Bordspannung, Antwort eines Steuergeräts). Fällt
 * eine Stufe aus, steht im Klartext, welche.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    WerkstattStatus()
                }
            }
        }
    }
}

@Composable
private fun WerkstattStatus() {
    var befunde by remember { mutableStateOf<List<Befund>>(emptyList()) }
    var laeuft by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val verbindung = remember { UsbDongleVerbindung(context) }
    val bluetooth = remember { BleGattVerbindung(context) }
    val scope = rememberCoroutineScope()

    var dongleBefunde by remember { mutableStateOf<List<Befund>?>(null) }
    var dongleLaeuft by remember { mutableStateOf(false) }

    // Bericht des VCI-Checkers (`vci-check --json`), eingefügt und ausgewertet.
    var berichtJson by remember { mutableStateOf("") }
    var bericht by remember { mutableStateOf<VciBericht?>(null) }
    var berichtFehler by remember { mutableStateOf<String?>(null) }
    var berichtAblage by remember { mutableStateOf<String?>(null) }
    var berichtTeilenOk by remember { mutableStateOf(true) }
    var berichtTeilenLaeuft by remember { mutableStateOf(false) }

    suspend fun donglePruefen(quelle: suspend () -> List<Befund>) {
        dongleLaeuft = true
        dongleBefunde = quelle()
        dongleLaeuft = false
    }

    // Bluetooth-Rechte sind ab Android 12 Laufzeitrechte; ohne sie kein Scan.
    val rechteAnfrage = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch {
            if (BleGattVerbindung.rechteVorhanden(context)) {
                donglePruefen { bluetooth.pruefen() }
            } else {
                dongleBefunde = listOf(
                    Befund("Bluetooth", "ohne Berechtigung kein Scan möglich", ok = false)
                )
            }
        }
    }

    DisposableEffect(verbindung, bluetooth) {
        onDispose {
            verbindung.close()
            bluetooth.close()
        }
    }

    suspend fun kernPruefen() {
        laeuft = true
        // Der Aufruf geht über JNA in die native Bibliothek — nicht im UI-Thread.
        befunde = withContext(Dispatchers.IO) { VciCheck.ausfuehren() }
        laeuft = false
    }

    LaunchedEffect(Unit) { kernPruefen() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "Werkstatt VCI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Selbsttest",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        if (laeuft) {
            Text("Prüfe …", style = MaterialTheme.typography.bodyLarge)
        } else {
            befunde.forEach { BefundZeile(it) }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Dongle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        when {
            dongleLaeuft -> Text("Prüfe Dongle …", style = MaterialTheme.typography.bodyLarge)
            dongleBefunde == null -> Text(
                text = "Stecke den VCI an den USB-Anschluss und starte die Prüfung. " +
                    "Android fragt beim ersten Mal nach der Freigabe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> dongleBefunde!!.forEach { BefundZeile(it) }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { scope.launch { kernPruefen() } },
                enabled = !laeuft
            ) {
                Text("Kern erneut prüfen")
            }
            Button(
                onClick = { scope.launch { donglePruefen { verbindung.pruefen() } } },
                enabled = !dongleLaeuft
            ) {
                Text("USB prüfen")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (BleGattVerbindung.rechteVorhanden(context)) {
                        scope.launch { donglePruefen { bluetooth.pruefen() } }
                    } else {
                        rechteAnfrage.launch(BleGattVerbindung.benoetigteRechte().toTypedArray())
                    }
                },
                enabled = !dongleLaeuft
            ) {
                Text("Bluetooth prüfen")
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Checker-Bericht", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Ausgabe von vci-check --json einfügen. Die App zeigt den Bericht an und " +
                "stellt den Handlungshinweis nach vorn — sie rechnet nichts nach.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = berichtJson,
            onValueChange = { berichtJson = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("JSON-Bericht") },
            minLines = 3,
            maxLines = 8
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                bericht = null
                berichtFehler = null
                berichtAblage = null
                try {
                    bericht = VciBericht.ausJson(berichtJson)
                } catch (e: VciBericht.Fehler) {
                    berichtFehler = e.message
                }
            },
            enabled = berichtJson.isNotBlank()
        ) {
            Text("Bericht auswerten")
        }

        berichtFehler?.let { meldung ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = meldung,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        bericht?.let { b ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = b.zusammenfassung,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (b.ergebnis == VciStatus.OK) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Gerät ${b.geraet} · Firmware ${b.firmware} · " +
                    "${b.resyncs} Resync · ${b.crcFehler} CRC-Fehler",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            b.pruefungen.forEach { PruefungZeile(it) }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val jetzt = LocalDateTime.now()
                    val name = BerichtDatei.name(b, jetzt)
                    val inhalt = BerichtDatei.inhalt(b, berichtJson, jetzt)
                    berichtAblage = null
                    berichtTeilenLaeuft = true
                    scope.launch {
                        // Datei schreiben und Teilen-Dialog öffnen — nicht im UI-Thread.
                        val meldung = withContext(Dispatchers.IO) {
                            BerichtTeilen.teilen(context, name, inhalt)
                        }
                        berichtTeilenOk = meldung == null
                        berichtAblage = meldung ?: BerichtTeilen.ablage(name)
                        berichtTeilenLaeuft = false
                    }
                },
                enabled = !berichtTeilenLaeuft
            ) {
                Text("Bericht als Datei teilen")
            }

            berichtAblage?.let { ziel ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (berichtTeilenOk) "Gespeichert: $ziel" else ziel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (berichtTeilenOk) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = "Der Kern rechnet ISO-TP, der Dongle macht CAN. Ohne Fahrzeug " +
                "am Bus bleibt die Steuergeräte-Prüfung ehrlich rot — Bordspannung " +
                "und die Strecke zum Dongle lassen sich trotzdem messen. USB und " +
                "Bluetooth nutzen dasselbe Drahtformat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BefundZeile(befund: Befund) {
    val farbe = if (befund.ok) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (befund.ok) "OK" else "FEHLER",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = farbe
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(befund.titel, style = MaterialTheme.typography.titleMedium)
                Text(
                    befund.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Eine Zeile aus dem Checker-Bericht.
 *
 * Der Status steht als Wort da (OK, HINWEIS, FEHLER, ÜBERSPRUNGEN), nicht nur
 * als Farbe — in der Halle ist der Bildschirm oft schmutzig und das Licht
 * schlecht, und Farbe allein ist keine Information (Barrierefreiheit).
 */
@Composable
private fun PruefungZeile(pruefung: VciPruefung) {
    val farbe = when (pruefung.status) {
        VciStatus.OK -> MaterialTheme.colorScheme.primary
        VciStatus.WARNUNG -> MaterialTheme.colorScheme.tertiary
        VciStatus.FEHLER -> MaterialTheme.colorScheme.error
        VciStatus.UEBERSPRUNGEN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pruefung.status.anzeige,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = farbe
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    pruefung.meldung.ifBlank { pruefung.id },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${pruefung.id} · ${pruefung.messwert}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pruefung.hinweis.isNotBlank()) {
                    Text(
                        pruefung.hinweis,
                        style = MaterialTheme.typography.bodySmall,
                        color = farbe
                    )
                }
            }
        }
    }
}
