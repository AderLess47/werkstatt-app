package de.werkstatt.vci

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vci_ffi.Sicherheit
import uniffi.vci_ffi.SicherheitFfi

/**
 * Frist für einen Sicherheitszugriff.
 *
 * Zwei kurze Nachrichten je Richtung (Seed fragen, Schlüssel schicken), beide
 * ein einzelner Rahmen; 5 s sind P2 plus Reserve für einen trägen Dongle. Bei
 * `NRC 0x78` verlängert der Kern selbst, hier ist keine zweite Frist nötig.
 */
private const val SICHERHEIT_FRIST_MS = 5_000u

/**
 * `27` (SecurityAccess) am Bildschirm: Ebene, Seed und Ergebnis.
 *
 * Der Kern macht die Arbeit (Seed anfordern, NRC auswerten, Schlüssel senden,
 * Bestätigung prüfen). Dieser Abschnitt zeigt, **was** dabei herauskam — die
 * Ebene, den Seed so, wie er auf dem Bus lag, und den Ausgang. Der Schlüssel
 * kommt aus der Eingabe: die App rechnet keinen aus, und der Anbieter unten
 * gibt genau die eingetragenen Bytes weiter.
 *
 * Die Anzeige ist der Punkt: „bereits offen“ und „entsperrt“ sind verschiedene
 * Vorgänge, und ein Seed aus lauter Nullbytes ist keine Challenge, sondern die
 * Auskunft, dass nichts zu tun war. Deshalb stehen diese Fälle als eigener Satz
 * da und nicht als Hexwert ohne Erklärung.
 *
 * Der Abschnitt öffnet seinen Dongle selbst, wie der SFD-Abschnitt daneben —
 * solange ein Gerät geöffnet ist, kann es kein zweiter Abschnitt gleichzeitig
 * öffnen. Wer beides braucht, schließt den einen vorher.
 *
 * [onSchritt] meldet den angekündigten Schritt nach oben (Selbsttest-Bericht).
 */
@Composable
internal fun SicherheitAbschnitt(onSchritt: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verbindung = remember { UsbDongleVerbindung(context) }

    var transport by remember { mutableStateOf<UsbCanTransport?>(null) }
    var geraet by remember { mutableStateOf<String?>(null) }

    // Ebene: Voreinstellung oder freie Eingabe. `null` = frei.
    var preset by remember { mutableStateOf<SicherheitsEbene?>(SicherheitsEbene.EINS) }
    var freieEbene by remember { mutableStateOf("01") }
    val freiePruefung = SicherheitEbenen.pruefen(freieEbene)
    val ebene: Int? = preset?.code ?: freiePruefung.wert

    // Schlüssel: Hex aus einem Portal oder einem lizenzierten Baustein.
    var schluesselText by remember { mutableStateOf("") }
    // Dieselbe Hex-Regel wie beim SFD-Token — eine Regel, nicht zwei.
    val schluesselPruefung = SfdToken.pruefen(schluesselText)

    var seed by remember { mutableStateOf<ByteArray?>(null) }
    var ergebnis by remember { mutableStateOf<String?>(null) }
    var meldung by remember { mutableStateOf<SfdFehler.Meldung?>(null) }
    var schritt by remember { mutableStateOf<String?>(null) }

    val sperre = remember { SfdSperre() }
    val laeuft = schritt != null

    DisposableEffect(verbindung) {
        onDispose { verbindung.close() }
    }

    fun oeffnen() {
        if (!sperre.belegen("Suche USB-Dongle und frage die Freigabe ab — bis 30 s …")) return
        meldung = null
        ergebnis = null
        seed = null
        transport = null
        val text = "Suche USB-Dongle und frage die Freigabe ab — bis 30 s …"
        schritt = text
        onSchritt(text)
        scope.launch {
            try {
                when (val ergebnis = withContext(Dispatchers.IO) { verbindung.oeffnen() }) {
                    is UsbDongleVerbindung.Ergebnis.Abgelehnt ->
                        meldung = SfdFehler.Meldung(SfdFehler.Art.USB, ergebnis.text, null)
                    is UsbDongleVerbindung.Ergebnis.Bereit -> {
                        transport = ergebnis.transport
                        geraet = ergebnis.kennung
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                meldung = SicherheitAnzeige.meldung(ebene ?: 0, e)
            } finally {
                schritt = null
                sperre.freigeben()
            }
        }
    }

    fun ebeneOeffnen() {
        val offen = transport
        if (offen == null) {
            meldung = SfdFehler.keinTransport()
            return
        }
        val adresse = ebene
        if (adresse == null) {
            meldung = SfdFehler.Meldung(
                SfdFehler.Art.FORMAT,
                freiePruefung.fehler ?: "Ebene ist nicht gültig",
                "Ebene als Hex eingeben, z. B. 01",
            )
            return
        }
        val schluessel = if (schluesselPruefung.ok) {
            SicherheitAnzeige.bytes(schluesselPruefung.normalisiert)
        } else {
            // Kein Schlüssel eingetragen: der Anbieter antwortet leer, der Kern
            // sendet nichts und meldet das. Ein geratener Schlüssel wäre am
            // Steuergerät ein Fehlversuch mit Wartezeit (NRC 0x36/0x37).
            ByteArray(0)
        }
        val was = "Sende 27 %s an 0x7E0 und warte auf den Seed — Frist %s"
            .format(SicherheitAnzeige.ebeneText(adresse), fristText(SICHERHEIT_FRIST_MS.toLong()))
        if (!sperre.belegen(was)) return

        meldung = null
        ergebnis = null
        seed = null
        schritt = was
        onSchritt(was)
        val anbieter = SicherheitsAnbieter(schluessel)
        scope.launch {
            try {
                val zugriff = withContext(Dispatchers.IO) {
                    val sicherheit = Sicherheit()
                    try {
                        sicherheit.securityAccess(
                            offen,
                            adresse.toUInt(),
                            anbieter,
                            SICHERHEIT_FRIST_MS,
                        )
                    } finally {
                        sicherheit.close()
                    }
                }
                // Der Anbieter hat Ebene und Seed gesehen, bevor er antwortet.
                seed = anbieter.seed
                ergebnis = when (zugriff) {
                    SicherheitFfi.BEREITS_OFFEN -> SicherheitAnzeige.bereitsOffen(adresse)
                    SicherheitFfi.ENTSPERRT -> SicherheitAnzeige.entsperrt(adresse, schluessel)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Auch im Fehlerfall bleibt sichtbar, welcher Seed kam.
                seed = anbieter.seed
                meldung = SicherheitAnzeige.meldung(adresse, e)
            } finally {
                schritt = null
                sperre.freigeben()
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text(
        "Sicherheitszugriff (27)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Fragt den Seed einer Ebene (27 <Ebene>) und schickt den Schlüssel " +
            "zurück (27 <Ebene+1>). Die App rechnet keinen Schlüssel aus — er wird " +
            "eingetragen. Ohne Schlüssel wird nichts gesendet; ein Seed aus lauter " +
            "Nullbytes heißt „Ebene war bereits offen“.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { oeffnen() }, enabled = !laeuft) {
            Text(if (transport == null) "USB-Dongle öffnen" else "Neu öffnen")
        }
        Button(
            onClick = { ebeneOeffnen() },
            enabled = !laeuft && transport != null && ebene != null,
        ) {
            Text("Ebene öffnen")
        }
    }

    geraet?.let { kennung ->
        Spacer(Modifier.height(8.dp))
        Text(
            "Verbunden mit $kennung",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // --- Ebene --------------------------------------------------------------

    Spacer(Modifier.height(16.dp))
    Text(
        "Ebene",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SicherheitsEbene.entries.forEach { kandidat ->
            FilterChip(
                selected = preset == kandidat,
                onClick = { preset = kandidat },
                enabled = !laeuft,
                label = { Text(kandidat.anzeige) },
            )
        }
        FilterChip(
            selected = preset == null,
            onClick = { preset = null },
            enabled = !laeuft,
            label = { Text("frei") },
        )
    }

    if (preset == null) {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = freieEbene,
            onValueChange = { freieEbene = it },
            modifier = Modifier.width(160.dp),
            label = { Text("Ebene (Hex)") },
            singleLine = true,
            enabled = !laeuft,
            isError = !freiePruefung.ok,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = freiePruefung.fehler
                ?: "Ebenen sind ungerade (01, 03, 05 …); die gerade Zahl daneben ist die " +
                "Schlüssel-Unterfunktion.",
            style = MaterialTheme.typography.bodySmall,
            color = if (!freiePruefung.ok) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Spacer(Modifier.height(2.dp))
        Text(
            preset?.hinweis.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ebene?.let { adresse ->
        Spacer(Modifier.height(4.dp))
        Text(
            "Anfrage: 27 ${SicherheitAnzeige.ebeneText(adresse)} · Schlüssel: " +
                "27 ${SicherheitAnzeige.ebeneText(adresse + 1)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // --- Schlüssel ----------------------------------------------------------

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = schluesselText,
        onValueChange = { schluesselText = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Schlüssel (Hex, leer lassen zum Seed-Lesen)") },
        minLines = 1,
        maxLines = 4,
        isError = !schluesselPruefung.ok && !schluesselPruefung.leer,
        enabled = !laeuft,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = when {
            schluesselPruefung.ok ->
                "${schluesselPruefung.bytes} Byte · wird gesendet"
            schluesselPruefung.leer ->
                "kein Schlüssel eingetragen — es wird nur der Seed gelesen, nichts gesendet"
            else -> schluesselPruefung.fehler.orEmpty()
        },
        style = MaterialTheme.typography.bodySmall,
        color = when {
            schluesselPruefung.ok -> MaterialTheme.colorScheme.primary
            schluesselPruefung.leer -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.error
        },
    )

    // --- Ergebnis -----------------------------------------------------------

    Spacer(Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Ebene: " + (ebene?.let(SicherheitAnzeige::ebeneText) ?: "keine gültige Ebene"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Seed: ${SicherheitAnzeige.seedText(seed)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Ergebnis: " + (ergebnis ?: "noch nicht abgefragt"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ergebnis != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // --- Rückmeldung --------------------------------------------------------

    schritt?.let { text ->
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }

    meldung?.let { m ->
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${m.art.anzeige}: ${m.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                m.hinweis?.let { hinweis ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hinweis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
