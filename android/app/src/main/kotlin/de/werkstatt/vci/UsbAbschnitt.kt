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
 * Frist für **einen** Rahmen der Antwort — nicht für die ganze Antwort.
 *
 * `IsotpChannel::recv` im Kern gibt diesen Wert an jedes `recv` weiter: erst an
 * das Warten auf den ersten Rahmen (First Frame), dann an jede Folgeantwort
 * (Consecutive Frame). Bei der Fahrgestellnummer (20 Byte = 3 Rahmen) können
 * daraus bis zu 4,5 s werden, bevor die Frist greift.
 *
 * Die Zahl ist abgeleitet, nicht geschätzt: ISO 15765-2 setzt N_Cr — die Zeit
 * bis zum nächsten Consecutive Frame — auf 1 000 ms. 1 500 ms sind N_Cr plus
 * 50 % Reserve für Bulk-Transfer und Scheduler.
 *
 * **0x78 („response pending“):** meldet das Steuergerät `7F <Dienst> 78`, ist das
 * kein Ergebnis, sondern ein Aufschub. Dann wird `recv` mit [P2_STERN_MS]
 * erneut aufgerufen — nach ISO 14229-1 kommt die gültige Antwort ohne neue
 * Anfrage, und der Kern liest sie als eigene ISO-TP-Nachricht. Höchstens
 * [MAX_AUFSCHUB] Mal, damit ein dauerhaft aufschiebendes Steuergerät die App
 * nicht beliebig lange warten lässt.
 */
private const val ANTWORT_FRIST_MS = 1_500u

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
 *
 * [onSchritt] meldet jeden angekündigten Schritt nach oben, damit er im
 * Selbsttest-Bericht landet — der Support sieht dann, wobei gewartet wurde.
 */
@Composable
internal fun UsbAbschnitt(onSchritt: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verbindung = remember { UsbDongleVerbindung(context) }

    var transport by remember { mutableStateOf<UsbCanTransport?>(null) }
    var befunde by remember { mutableStateOf<List<Befund>>(emptyList()) }
    var meldung by remember { mutableStateOf<String?>(null) }

    /**
     * Was gerade läuft. Dieselbe Zeile erklärt nach einem Abbruch, wobei gewartet
     * wurde — deshalb ein Text und kein bloßes `Boolean`.
     */
    var schritt by remember { mutableStateOf<String?>(null) }
    val laeuft = schritt != null

    /** Zeigt den Schritt und meldet ihn nach oben — von dort geht er in den Bericht. */
    fun schrittZeigen(text: String) {
        schritt = text
        onSchritt(text)
    }

    DisposableEffect(verbindung) {
        onDispose { verbindung.close() }
    }

    fun oeffnen() {
        schrittZeigen(
            "Suche USB-Dongle und frage die Freigabe ab — der Dialog darf bis 30 s offen bleiben"
        )
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
            schritt = null
        }
    }

    fun dienstLesen() {
        val offen = transport ?: return
        meldung = null
        val anfrage = byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())
        schrittZeigen("Sende Anfrage 22 F190 …")
        scope.launch {
            val gesendet = withContext(Dispatchers.IO) {
                runCatching { IsoTp().send(offen, anfrage) }
            }
            val sendeFehler = gesendet.exceptionOrNull()
            if (sendeFehler != null) {
                befunde = befunde + Befund(
                    "Dienst 22 F190",
                    "Anfrage nicht gesendet: ${sendeFehler.message ?: sendeFehler.toString()}",
                    ok = false
                )
                schritt = null
                return@launch
            }
            // Diese Zeile steht genau so lange, wie der Kern auf Rahmen wartet —
            // sie nennt die Frist, damit ein Abbruch zuordenbar ist.
            schrittZeigen(
                "Warte auf die Antwort auf 22 F190 — Frist " +
                    fristText(ANTWORT_FRIST_MS.toLong()) + " je Rahmen, bei 20 Byte bis " +
                    fristText(ANTWORT_FRIST_MS.toLong() * 3)
            )
            // Nach einem 0x78 wird erneut gewartet, nicht erneut gesendet: das
            // Steuergerät rechnet weiter und schickt die Antwort von selbst.
            var frist = ANTWORT_FRIST_MS
            var aufschuebe = 0
            var antwort: Result<ByteArray> =
                Result.failure(IllegalStateException("keine Antwort angefordert"))
            while (true) {
                antwort = withContext(Dispatchers.IO) { runCatching { IsoTp().recv(offen, frist) } }
                val bytes = antwort.getOrNull()
                if (bytes == null || !DienstAntwort.istAntwortNochOffen(bytes)) break
                if (aufschuebe >= MAX_AUFSCHUB) break
                aufschuebe++
                frist = P2_STERN_MS
                schrittZeigen(
                    "Steuergerät meldet 0x78 (Antwort kommt noch) — warte erneut bis " +
                        fristText(P2_STERN_MS.toLong()) + ", Aufschub $aufschuebe von " +
                        "$MAX_AUFSCHUB"
                )
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
