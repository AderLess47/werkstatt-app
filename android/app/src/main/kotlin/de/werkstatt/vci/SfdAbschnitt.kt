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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.werkstatt.vci.transport.UsbCanTransport
import de.werkstatt.vci.transport.UsbDongleVerbindung
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vci_ffi.Audit
import uniffi.vci_ffi.Sfd

/**
 * Frist für Status, Session, Challenge und Sperren.
 *
 * Die vier sind kurze Nachrichten (Anfrage und Antwort je ein Rahmen); 5 s sind
 * P2 plus Reserve für einen trägen Dongle.
 */
private const val SFD_FRIST_MS = 5_000u

/**
 * Frist für den Token.
 *
 * Der Token ist mehrere Hundert Byte lang und geht als Multi-Frame hinaus: ein
 * Steuergerät mit `BS > 0` verlangt nach jedem Block ein Flow-Control, und mit
 * `STmin` kommt je Rahmen Wartezeit dazu. 20 s decken 324 Byte auch bei
 * ungünstigen Werten; zu knapp gewählt bricht die Übertragung mitten im Token
 * ab — und ein halb gesendeter Token ist am Steuergerät ein Fehlversuch.
 */
private const val TOKEN_FRIST_MS = 20_000u

/**
 * Was im Audit-Eintrag als Fahrzeug steht, solange die App keine VIN hat.
 *
 * Eine erfundene VIN wäre schlimmer als ein ehrliches „unbekannt“: der
 * Nachweis soll später zuzuordnen sein, nicht gut aussehen.
 */
private const val VIN_UNBEKANNT = "unbekannt"

/**
 * SFD-Ablauf am Bildschirm: Ziel, Status, Challenge, Token, Sperren.
 *
 * Der Kern macht die Arbeit (Nachrichten bauen, ISO-TP, NRC auswerten), dieser
 * Abschnitt sammelt nur ein, was er zurückgibt — inklusive der Rohantwort, damit
 * eine Deutung, die nicht stimmt, am Fahrzeug auffällt statt geglaubt zu werden.
 *
 * Jeder Busaufruf läuft über [SfdSperre] und `Dispatchers.IO`: solange gewartet
 * wird, sind die Knöpfe aus, und der Main-Thread bleibt frei.
 *
 * [onSchritt] meldet jeden angekündigten Schritt nach oben — er landet im
 * Selbsttest-Bericht, damit der Support sieht, wobei gewartet wurde.
 *
 * [audit] ist die Kette des Bildschirms: Entsperren und Sperren schreiben dort
 * hinein (der Kern tut das, hier wird nur durchgereicht). [onNeu] sagt dem
 * Audit-Abschnitt, dass es etwas Neues zu zeigen gibt.
 */
@Composable
internal fun SfdAbschnitt(
    audit: Audit,
    onSchritt: (String) -> Unit = {},
    onNeu: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zwischenablage = LocalClipboardManager.current
    val verbindung = remember { UsbDongleVerbindung(context) }

    var transport by remember { mutableStateOf<UsbCanTransport?>(null) }
    var geraet by remember { mutableStateOf<String?>(null) }

    // Ziel-Steuergerät: Voreinstellung oder freie Kennungen. `null` = frei.
    var preset by remember { mutableStateOf<SfdZiel?>(SfdZiele.STANDARD) }
    var freieAnfrage by remember { mutableStateOf("7E0") }
    var freieAntwort by remember { mutableStateOf("7E8") }
    val freiesErgebnis = SfdZiele.frei(freieAnfrage, freieAntwort)
    val ziel: SfdZiel? = preset ?: (freiesErgebnis as? SfdZiele.Ergebnis.Bereit)?.ziel

    // Extended Session vorab? Viele Steuergeräte verweigern 31 01 C0 04/08 sonst.
    var extended by remember { mutableStateOf(true) }

    var rolle by remember { mutableStateOf(SfdRolle.EXTENDED) }
    var tokenText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<SfdStatusDeutung.Deutung?>(null) }
    var challenge by remember { mutableStateOf<String?>(null) }
    var meldung by remember { mutableStateOf<SfdFehler.Meldung?>(null) }
    var erfolg by remember { mutableStateOf<String?>(null) }
    var schritt by remember { mutableStateOf<String?>(null) }

    val sperre = remember { SfdSperre() }
    val laeuft = schritt != null
    val tokenPruefung = SfdToken.pruefen(tokenText)

    DisposableEffect(verbindung) {
        onDispose { verbindung.close() }
    }

    /**
     * Führt einen Busaufruf aus: Knöpfe sperren, im Hintergrund rechnen,
     * Ergebnis auf dem Main-Thread ablegen, Sperre wieder frei.
     */
    fun <T> starte(
        was: String,
        block: (UsbCanTransport, SfdZiel) -> T,
        erfolgMit: (T) -> Unit,
    ) {
        val offen = transport
        if (offen == null) {
            meldung = SfdFehler.keinTransport()
            return
        }
        val adresse = ziel
        if (adresse == null) {
            meldung = SfdFehler.Meldung(
                SfdFehler.Art.FORMAT,
                "Ziel-Kennungen sind nicht gültig",
                "Anfrage- und Antwort-ID als Hex eingeben, gleiche Bitbreite",
            )
            return
        }
        // Doppelklick: der zweite Druck startet nichts, solange der erste läuft.
        if (!sperre.belegen(was)) return

        meldung = null
        erfolg = null
        schritt = was
        onSchritt(was)
        scope.launch {
            try {
                val ergebnis = withContext(Dispatchers.IO) { block(offen, adresse) }
                erfolgMit(ergebnis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                meldung = SfdFehler.aus(e)
            } finally {
                schritt = null
                sperre.freigeben()
            }
        }
    }

    fun oeffnen() {
        if (!sperre.belegen("Suche USB-Dongle und frage die Freigabe ab — bis 30 s …")) return
        meldung = null
        erfolg = null
        status = null
        challenge = null
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
                meldung = SfdFehler.aus(e)
            } finally {
                schritt = null
                sperre.freigeben()
            }
        }
    }

    fun statusPruefen() = starte(
        was = "Sende 22 01 74 an ${ziel?.anzeige ?: "?"} und warte auf den Status — Frist " +
            fristText(SFD_FRIST_MS.toLong()),
        block = { offen, adresse ->
            mitSfd(adresse, audit) { sfd -> sfd.status(offen, SFD_FRIST_MS) }
        },
        erfolgMit = { st ->
            // Rohantwort mitgeben: passt das Layout nicht, bleibt sie sichtbar.
            status = SfdStatusDeutung.deute(st.daten, st.rohHex)
            erfolg = "Status gelesen (${st.rohHex.length / 2} Byte Antwort)"
        },
    )

    fun challengeAnfordern() = starte(
        was = "Sende ${SfdAblauf.vorChallenge(extended).joinToString(" → ") { it.anzeige }} " +
            "mit Rolle 0x%02X (%s) — Frist %s"
                .format(rolle.code, rolle.anzeige, fristText(SFD_FRIST_MS.toLong())),
        block = { offen, adresse ->
            mitSfd(adresse, audit) { sfd ->
                if (extended) sfd.extendedSession(offen, SFD_FRIST_MS)
                sfd.challenge(offen, rolle.code.toUInt(), SFD_FRIST_MS)
            }
        },
        erfolgMit = { hex ->
            challenge = hex
            erfolg = "Challenge empfangen (${hex.length / 2} Byte) — kopieren und im Portal einlösen"
        },
    )

    fun tokenSenden() {
        val geprueft = SfdToken.pruefen(tokenText)
        if (!geprueft.ok) {
            meldung = SfdFehler.Meldung(
                SfdFehler.Art.FORMAT,
                geprueft.fehler ?: "kein Token eingegeben",
                "Der Token kommt aus dem Herstellerportal — als Hex-Text einfügen",
            )
            return
        }
        starte(
            was = "Sende ${SfdAblauf.vorEntsperren(extended).joinToString(" → ") { it.anzeige }} " +
                "mit ${geprueft.bytes} Byte Token als Multi-Frame — " +
                "Frist ${fristText(TOKEN_FRIST_MS.toLong())}, danach Status lesen",
            block = { offen, adresse ->
                mitSfd(adresse, audit) { sfd ->
                    if (extended) sfd.extendedSession(offen, SFD_FRIST_MS)
                    sfd.entsperren(offen, geprueft.normalisiert, TOKEN_FRIST_MS)
                }
                // Direkt nachlesen, statt den alten Status stehen zu lassen: ob
                // das Entsperren gewirkt hat, sagt nur das Steuergerät.
                mitSfd(adresse, audit) { sfd -> sfd.status(offen, SFD_FRIST_MS) }
            },
            erfolgMit = { st ->
                val deutung = SfdStatusDeutung.deute(st.daten, st.rohHex)
                status = deutung
                erfolg = "Token gesendet — Status danach: ${deutung.text}"
                // Der Eintrag liegt jetzt in der Kette — der Audit-Abschnitt
                // soll ihn zeigen, ohne dass jemand nachsieht.
                onNeu()
            },
        )
    }

    fun sperren() = starte(
        was = "Sende ${SfdAblauf.vorSperren(extended).joinToString(" → ") { it.anzeige }} " +
            "und lese den Status danach — Frist ${fristText(SFD_FRIST_MS.toLong())}",
        block = { offen, adresse ->
            mitSfd(adresse, audit) { sfd ->
                if (extended) sfd.extendedSession(offen, SFD_FRIST_MS)
                sfd.sperren(offen, SFD_FRIST_MS)
            }
            mitSfd(adresse, audit) { sfd -> sfd.status(offen, SFD_FRIST_MS) }
        },
        erfolgMit = { st ->
            val deutung = SfdStatusDeutung.deute(st.daten, st.rohHex)
            status = deutung
            erfolg = "SFD gesperrt — Status danach: ${deutung.text}"
            onNeu()
        },
    )

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text(
        "SFD (Schutz Fahrzeug Diagnose)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Sperrt sicherheitsrelevante Steuergeräte, bis ein Token aus dem " +
            "Herstellerportal gesendet wird. Die App baut nur die UDS-Nachrichten " +
            "(22 01 74, 10 03, 31 01 C0 04/08/09) und wertet die Antworten aus — sie " +
            "erzeugt keine Schlüssel. Die Rohantwort steht immer neben der Deutung.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = { oeffnen() }, enabled = !laeuft) {
            Text(if (transport == null) "USB-Dongle öffnen" else "Neu öffnen")
        }
        Button(
            onClick = { statusPruefen() },
            enabled = !laeuft && transport != null && ziel != null
        ) {
            Text("SFD-Status prüfen")
        }
    }

    geraet?.let { kennung ->
        Spacer(Modifier.height(8.dp))
        Text(
            "Verbunden mit $kennung",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // --- Ziel-Steuergerät ---------------------------------------------------

    Spacer(Modifier.height(16.dp))
    Text(
        "Ziel-Steuergerät",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "SFD läuft nicht nur gegen das Motorsteuergerät. Die Voreinstellungen sind " +
            "die üblichen; welches Steuergerät welche Kennung hat, steht in der " +
            "Diagnosebeschreibung des Fahrzeugs. Kennungen werden hexadezimal gelesen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SfdZiele.presets.forEach { kandidat ->
            FilterChip(
                selected = preset == kandidat,
                onClick = { preset = kandidat },
                enabled = !laeuft,
                label = { Text(kandidat.name) },
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = freieAnfrage,
                onValueChange = { freieAnfrage = it },
                modifier = Modifier.width(160.dp),
                label = { Text("Anfrage-ID") },
                singleLine = true,
                enabled = !laeuft,
                isError = SfdZiele.idPruefen(freieAnfrage).fehler != null,
            )
            OutlinedTextField(
                value = freieAntwort,
                onValueChange = { freieAntwort = it },
                modifier = Modifier.width(160.dp),
                label = { Text("Antwort-ID") },
                singleLine = true,
                enabled = !laeuft,
                isError = SfdZiele.idPruefen(freieAntwort).fehler != null,
            )
        }
        Spacer(Modifier.height(2.dp))
        val hinweis = (freiesErgebnis as? SfdZiele.Ergebnis.Ungueltig)?.fehler
        Text(
            text = hinweis ?: "11-Bit bis 0x7FF, darüber 29-Bit (z. B. 18DA10F1). " +
                "Beide Kennungen müssen dieselbe Bitbreite haben.",
            style = MaterialTheme.typography.bodySmall,
            color = if (hinweis != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    ziel?.let { adresse ->
        Spacer(Modifier.height(4.dp))
        Text(
            "Adressierung: ${adresse.anzeige}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // --- Diagnosesitzung ----------------------------------------------------

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = extended,
            onCheckedChange = { extended = it },
            enabled = !laeuft,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Extended Session vorab senden (10 03)",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Viele Steuergeräte lehnen 31 01 C0 04 / C0 08 in der Default-Session " +
                    "mit NRC 0x7E oder 0x7F ab. Steht der Hinweis „in der aktiven Sitzung " +
                    "nicht unterstützt“ im Fehlerfeld, ist dieser Schalter die Antwort.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // --- Status -------------------------------------------------------------

    status?.let { deutung -> StatusKarte(deutung) }

    // --- Challenge ----------------------------------------------------------

    Spacer(Modifier.height(16.dp))
    Text("Challenge", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Die Challenge ist der Wert, den das Portal zum Erzeugen des Tokens braucht. " +
            "Rolle wählen, anfordern, kopieren.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SfdRolle.entries.forEach { kandidat ->
            FilterChip(
                selected = rolle == kandidat,
                onClick = { rolle = kandidat },
                enabled = !laeuft,
                label = { Text("${kandidat.anzeige} (0x%02X)".format(kandidat.code)) },
            )
        }
    }
    Spacer(Modifier.height(2.dp))
    Text(
        rolle.hinweis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { challengeAnfordern() },
            enabled = !laeuft && transport != null && ziel != null
        ) {
            Text("Challenge anfordern")
        }
        OutlinedButton(
            onClick = {
                challenge?.let { hex -> zwischenablage.setText(AnnotatedString(hex)) }
            },
            enabled = !challenge.isNullOrBlank()
        ) {
            Text("Challenge kopieren")
        }
    }

    challenge?.let { hex ->
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${hex.length / 2} Byte Challenge",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    hex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // --- Token --------------------------------------------------------------

    Spacer(Modifier.height(16.dp))
    Text("Token", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "SFD-Token als Hex-Text einfügen. Trenner (Leerzeichen, „:“, „-“) sind " +
            "erlaubt; alles andere wird abgelehnt, nicht übersprungen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = tokenText,
        onValueChange = { tokenText = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("SFD-Token (Hex)") },
        minLines = 2,
        maxLines = 6,
        isError = !tokenPruefung.ok && !tokenPruefung.leer,
        enabled = !laeuft,
    )

    Spacer(Modifier.height(4.dp))
    Text(
        text = when {
            tokenPruefung.ok -> "${tokenPruefung.bytes} Byte · bereit zum Senden"
            tokenPruefung.leer -> "noch kein Token eingegeben — höchstens " +
                "${SfdToken.MAX_BYTES} Byte"
            else -> tokenPruefung.fehler.orEmpty()
        },
        style = MaterialTheme.typography.bodySmall,
        color = when {
            tokenPruefung.ok -> MaterialTheme.colorScheme.primary
            tokenPruefung.leer -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.error
        }
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = {
                val ausAblage = zwischenablage.getText()?.text.orEmpty()
                if (ausAblage.isNotBlank()) tokenText = ausAblage
            },
            enabled = !laeuft
        ) {
            Text("Aus Zwischenablage einfügen")
        }
        Button(
            onClick = { tokenSenden() },
            enabled = !laeuft && transport != null && ziel != null && tokenPruefung.ok
        ) {
            Text("Token senden / Entsperren")
        }
    }

    // --- Sperren ------------------------------------------------------------

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = { sperren() },
            enabled = !laeuft && transport != null && ziel != null
        ) {
            Text("SFD manuell sperren")
        }
    }

    // --- Rückmeldung --------------------------------------------------------

    schritt?.let { text ->
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }

    erfolg?.let { text ->
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }

    meldung?.let { m ->
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${m.art.anzeige}: ${m.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                m.hinweis?.let { hinweis ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hinweis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * Statuskarte: Zustand als Wort, Rolle, Restlaufzeit — und die Rohantwort.
 *
 * Farbe allein reicht nicht: in der Halle ist das Licht schlecht, und
 * „gesperrt/entsperrt“ muss auch ohne Farbsehen lesbar sein. Die Rohantwort
 * steht immer da, weil SFD1 und SFD2 unterschiedlich lange antworten und eine
 * Deutung ohne Rohwert nicht überprüfbar wäre.
 */
@Composable
private fun StatusKarte(deutung: SfdStatusDeutung.Deutung) {
    val farbe = when (deutung.gesperrt) {
        true -> MaterialTheme.colorScheme.error
        false -> MaterialTheme.colorScheme.primary
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val zustand = when (deutung.gesperrt) {
        true -> "GESPERRT"
        false -> "ENTSPERRT"
        null -> "UNBEKANNT"
    }

    Spacer(Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                zustand,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = farbe
            )
            Spacer(Modifier.height(4.dp))
            Text(deutung.text, style = MaterialTheme.typography.bodyMedium)

            deutung.rolle?.let { rolle ->
                Text(
                    "aktive Rolle: 0x%02X (%s)".format(rolle, deutung.rolleText.orEmpty()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            deutung.restMinuten?.let { minuten ->
                val stunden = minuten / 60
                val rest = minuten % 60
                Text(
                    if (stunden > 0) {
                        "Restlaufzeit: $minuten min ($stunden h $rest min)"
                    } else {
                        "Restlaufzeit: $minuten min"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Rohantwort (${deutung.rohAntwort.length / 2} Byte): ${deutung.rohAntwort}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Statusbytes: ${deutung.roh.ifEmpty { "keine" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Deutung der Statusbytes ist vorläufig — am Fahrzeug mit bekanntem " +
                    "Zustand zu prüfen. Passt das Layout nicht, bleibt der Rohwert maßgeblich.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Ein SFD-Objekt je Aufruf, immer geschlossen, immer auf das gewählte Ziel.
 *
 * `withIds` ist nötig, weil der Kern sonst auf `0x7E0/0x7E8` festgelegt ist —
 * das Gateway antwortet dort nicht.
 */
private fun <T> mitSfd(ziel: SfdZiel, audit: Audit, block: (Sfd) -> T): T {
    // Die Kette geht beim Bauen mit — es gibt keinen Weg an ihr vorbei.
    val sfd = Sfd.withIds(
        audit,
        VIN_UNBEKANNT,
        ziel.anfrageId.toUInt(),
        ziel.antwortId.toUInt(),
    )
    try {
        return block(sfd)
    } finally {
        sfd.close()
    }
}
