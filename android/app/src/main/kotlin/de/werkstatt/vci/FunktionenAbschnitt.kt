package de.werkstatt.vci

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import de.werkstatt.vci.transport.UsbCanTransport
import de.werkstatt.vci.transport.UsbDongleVerbindung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.Funktion
import uniffi.vci_ffi.VciException

/** Katalogstand: was geladen wurde und was nicht. */
internal class Katalog(
    val funktionen: List<Funktion>,
    val fehler: List<String>,
)

/**
 * Liest die fünf Kataloginhalte aus den Assets.
 *
 * Die YAML-Dateien liegen **einmal** im Repo (`content/funktionen`) und werden
 * beim Bauen in die Assets kopiert (siehe `android/app/build.gradle.kts`). Eine
 * zweite Abschrift in der App wäre der Anfang von zwei Wahrheiten: das Schema
 * prüft der Kern, und was er prüft, soll auch das sein, was im Katalog steht.
 */
internal object FunktionenAusAssets {
    private const val ORDNER = "funktionen"

    fun laden(context: Context): Katalog {
        val funktionen = mutableListOf<Funktion>()
        val fehler = mutableListOf<String>()
        val namen = try {
            context.assets.list(ORDNER)?.filter { it.endsWith(".yaml") }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (namen.isEmpty()) {
            return Katalog(emptyList(), listOf("keine Kataloginhalte in den Assets gefunden"))
        }

        for (name in namen) {
            val text = try {
                context.assets.open("$ORDNER/$name").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                fehler += "$name: nicht lesbar (${e.message})"
                continue
            }
            try {
                funktionen += Funktion.ausYaml(name, text)
            } catch (e: VciException) {
                fehler += "$name: ${e.message}"
            }
        }
        return Katalog(funktionen, fehler)
    }
}

/**
 * Abschnitt „Service-Funktionen“: fünf Inhalte, Risikoklasse, Vorbedingungen,
 * Schritt-für-Schritt-Rückmeldung.
 *
 * Der Ablauf geht über den offenen Dongle; ohne Dongle steht das da und der
 * Startknopf ist aus. Jeder Lauf landet in derselben [Audit]-Kette wie die
 * übrigen Vorgänge — [onNeu] sagt dem Bildschirm, dass es etwas Neues zu
 * zeigen gibt.
 */
@Composable
internal fun FunktionenAbschnitt(
    audit: Audit,
    onSchritt: (String) -> Unit = {},
    onNeu: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verbindung = remember { UsbDongleVerbindung(context) }

    var transport by remember { mutableStateOf<UsbCanTransport?>(null) }
    var geraet by remember { mutableStateOf<String?>(null) }
    var oeffnet by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }

    val katalog = remember { FunktionenAusAssets.laden(context) }
    var gewaehlt by remember { mutableStateOf(katalog.funktionen.firstOrNull()?.id()) }

    // Was der Bediener bestätigt — der Kern glaubt es ihm nicht, er prüft die
    // Spannung selbst am Transport. Die beiden Häkchen sind die anderen two.
    var klemme15 by remember { mutableStateOf(true) }
    var motorAus by remember { mutableStateOf(true) }
    var bestaetigung by remember { mutableStateOf(false) }

    var laeuft by remember { mutableStateOf(false) }
    var lauf by remember { mutableStateOf<FunktionsLauf?>(null) }

    var vbatMv by remember { mutableStateOf<UInt?>(null) }

    val zustand = FahrzeugZustand(
        vin = "unbekannt",
        klemme15 = klemme15,
        motorAus = motorAus,
        vbatMv = vbatMv,
    )

    DisposableEffect(verbindung) {
        onDispose { verbindung.close() }
    }

    fun spannungLesen(t: UsbCanTransport) {
        scope.launch {
            vbatMv = withContext(Dispatchers.IO) { runCatching { t.vbatMv() }.getOrNull() }
        }
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("Service-Funktionen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Fünf Abläufe aus dem Katalog. Die Risikoklasse steht an jedem Eintrag; " +
            "Entwürfe ohne Freigabe („unbelegt“) hält der Kern selbst an, egal wer sie startet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                oeffnet = true
                meldung = null
                scope.launch {
                    when (val ergebnis = verbindung.oeffnen()) {
                        is UsbDongleVerbindung.Ergebnis.Bereit -> {
                            transport = ergebnis.transport
                            geraet = ergebnis.kennung
                            vbatMv = withContext(Dispatchers.IO) {
                                runCatching { ergebnis.transport.vbatMv() }.getOrNull()
                            }
                        }

                        is UsbDongleVerbindung.Ergebnis.Abgelehnt -> meldung = ergebnis.text
                    }
                    oeffnet = false
                }
            },
            enabled = !oeffnet && !laeuft,
        ) {
            Text(if (transport == null) "Dongle öffnen" else "Neu öffnen")
        }
        Text(
            text = when {
                oeffnet -> "öffne Dongle …"
                transport == null -> "kein Dongle offen"
                else -> geraet ?: "Dongle offen"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    meldung?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    if (katalog.fehler.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        katalog.fehler.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    Spacer(Modifier.height(12.dp))

    // Zwei Aussagen des Bedieners, die der Kern nicht messen kann.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = klemme15, onCheckedChange = { klemme15 = it }, enabled = !laeuft)
        Spacer(Modifier.height(0.dp))
        Text(
            "  Klemme 15 liegt an",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = motorAus, onCheckedChange = { motorAus = it }, enabled = !laeuft)
        Text(
            "  Motor steht",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))

    katalog.funktionen.forEach { funktion ->
        val istGewaehlt = funktion.id() == gewaehlt
        FunktionsKarte(
            funktion = funktion,
            gewaehlt = istGewaehlt,
            zustand = zustand,
            onWaehlen = {
                gewaehlt = funktion.id()
                lauf = null
                bestaetigung = false
            },
        )

        if (istGewaehlt) {
            Spacer(Modifier.height(4.dp))
            FunktionsDetail(
                funktion = funktion,
                zustand = zustand,
                sperre = FunktionsBediener.sperre(funktion),
                bestaetigung = bestaetigung,
                onBestaetigung = { bestaetigung = it },
                transportBereit = transport != null,
                laeuft = laeuft,
                lauf = lauf,
                onStart = {
                    val t = transport ?: return@FunktionsDetail
                    laeuft = true
                    lauf = null
                    onSchritt("Starte ${funktion.id()} …")
                    scope.launch {
                        val ergebnis = withContext(Dispatchers.IO) {
                            FunktionsBediener.laufen(
                                funktion = funktion,
                                transport = t,
                                zustand = zustand,
                                bestaetigung = bestaetigung,
                                audit = audit,
                                zeitMs = System.currentTimeMillis().toULong(),
                            )
                        }
                        lauf = ergebnis
                        // Nach dem Lauf neu messen: die Spannung am Ende ist
                        // die Zahl, die im Bericht interessiert.
                        vbatMv = withContext(Dispatchers.IO) {
                            runCatching { t.vbatMv() }.getOrNull()
                        }
                        laeuft = false
                        onSchritt(
                            if (ergebnis.erfolgreich) {
                                "${funktion.id()}: abgeschlossen, ${ergebnis.laufnummern.size} Schritte protokolliert"
                            } else {
                                "${funktion.id()}: ${ergebnis.gesperrt ?: ergebnis.fehler}"
                            }
                        )
                        onNeu()
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (transport != null) {
        OutlinedButton(
            onClick = { transport?.let { spannungLesen(it) } },
            enabled = !laeuft && !oeffnet,
        ) {
            Text("Bordspannung messen")
        }
    }
}

/** Eine Zeile im Katalog: Titel, Risikoklasse, Stand der Vorbedingungen. */
@Composable
private fun FunktionsKarte(
    funktion: Funktion,
    gewaehlt: Boolean,
    zustand: FahrzeugZustand,
    onWaehlen: () -> Unit,
) {
    val bedingungen = Vorabpruefung.zeilen(funktion, zustand)
    val erfuellt = bedingungen.count { it.erfuellt }
    val gesperrt = FunktionsBediener.sperre(funktion) != null
    val farbe = when {
        gesperrt -> MaterialTheme.colorScheme.onSurfaceVariant
        erfuellt == bedingungen.size -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onWaehlen() },
        colors = CardDefaults.cardColors(
            containerColor = if (gewaehlt) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = funktion.risk(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = farbe,
                )
                Text(
                    text = "  ${funktion.titel()}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = buildString {
                    append(funktion.id())
                    append(" · ")
                    append(funktion.schritte())
                    append(if (funktion.schritte() == 1u) " Schritt" else " Schritte")
                    append(" · ")
                    append(if (funktion.brauchtBestaetigung()) "Bestätigung nötig" else "ohne Bestätigung")
                    append(" · Vorbedingungen $erfuellt/${bedingungen.size}")
                    if (gesperrt) append(" · gesperrt (ohne Freigabe)")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Der gewählte Ablauf: Vorbedingungen, geplante Schritte, Start, Ergebnis. */
@Composable
private fun FunktionsDetail(
    funktion: Funktion,
    zustand: FahrzeugZustand,
    sperre: String?,
    bestaetigung: Boolean,
    onBestaetigung: (Boolean) -> Unit,
    transportBereit: Boolean,
    laeuft: Boolean,
    lauf: FunktionsLauf?,
    onStart: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Vorbedingungen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Vorabpruefung.zeilen(funktion, zustand).forEach { zeile ->
                Text(
                    text = (if (zeile.erfuellt) "OK · " else "OFFEN · ") + zeile.titel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (zeile.erfuellt) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    zeile.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text("Geplante Schritte", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            funktion.schrittliste().forEach { schritt ->
                Text(
                    "${schritt.nummer}. ${schritt.anfrageHex} → erwartet ${schritt.erwartetHex} " +
                        "(Frist ${schritt.timeoutMs} ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (sperre != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "gesperrt: $sperre",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (funktion.brauchtBestaetigung()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = bestaetigung,
                        onCheckedChange = onBestaetigung,
                        enabled = !laeuft,
                    )
                    Text(
                        "  ${funktion.risk()} schreibt mit Rückwirkung — ich bestätige den Start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            val bereit = transportBereit && !laeuft && sperre == null &&
                Vorabpruefung.bereit(funktion, zustand) &&
                (!funktion.brauchtBestaetigung() || bestaetigung)

            Button(onClick = onStart, enabled = bereit) {
                Text(if (laeuft) "läuft …" else "Starten")
            }
            if (!transportBereit) {
                Text(
                    "Ohne offenen Dongle kein Start — oben „Dongle öffnen“.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            lauf?.let { ergebnis ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "Rückmeldung",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                ergebnis.gesperrt?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ergebnis.schritte.forEach { schritt ->
                    Text(
                        text = "${schritt.nummer}. ${schritt.anfrage} → ${schritt.ausgang}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (schritt.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (ergebnis.backups.isNotEmpty()) {
                    Text(
                        "Backup vor dem Schreiben: ${ergebnis.backups.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ergebnis.laufnummern.isNotEmpty()) {
                    Text(
                        "Audit-Laufnummern: ${ergebnis.laufnummern.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ergebnis.fehler?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
