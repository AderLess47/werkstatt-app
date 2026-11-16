package de.werkstatt.vci

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vci_ffi.Audit

/** So viele Zeilen stehen am Bildschirm; der Export enthält immer alle. */
private const val SICHTBARE_EINTRAEGE = 20

/**
 * Abschnitt „Audit-Kette“: was protokolliert ist, ob die Kette hält, und der
 * Nachweis zum Mitnehmen.
 *
 * [zaehler] ist der Anlass zum Neuzeichnen: die Kette selbst ist ein Objekt der
 * Bibliothek und meldet keine Änderung. Der Zähler wird von den Abschnitten
 * hochgezählt, die etwas anhängen — so bleibt die Anzeige ehrlich, ohne dass
 * hier im Sekundentakt nachgesehen wird.
 */
@Composable
internal fun AuditAbschnitt(audit: Audit, zaehler: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zwischenablage = LocalClipboardManager.current

    var integritaet by remember(zaehler) { mutableStateOf(AuditAnzeige.integritaet(audit)) }
    val zeilen = remember(zaehler) { AuditAnzeige.zeilen(audit) }
    var ablage by remember { mutableStateOf<String?>(null) }
    var ablageOk by remember { mutableStateOf(true) }
    var teilt by remember { mutableStateOf(false) }

    val geraet = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }

    fun exportText(): String = AuditAnzeige.export(
        audit = audit,
        geraet = geraet,
        zeitpunkt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
    )

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("Audit-Kette", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Jeder versuchte Schritt steht hier — auch der gescheiterte. Die Prüfung " +
            "rechnet jeden Hash und jede Verkettung nach; sie stammt aus dem Kern, nicht aus dieser App.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = integritaet,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (integritaet.startsWith("Kette gebrochen")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "letzter Hash: ${AuditAnzeige.kurz(audit.letzterHash())} · ${audit.len()} Einträge",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row {
        OutlinedButton(onClick = { integritaet = AuditAnzeige.integritaet(audit) }) {
            Text("Kette prüfen")
        }
        Spacer(Modifier.height(0.dp))
        Button(
            onClick = { zwischenablage.setText(AnnotatedString(exportText())) },
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text("Als Text kopieren")
        }
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            ablage = null
            teilt = true
            scope.launch {
                val name = "audit-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                    ".txt"
                val meldung = withContext(Dispatchers.IO) {
                    BerichtTeilen.teilen(context, name, exportText())
                }
                ablageOk = meldung == null
                ablage = meldung ?: BerichtTeilen.ablage(name)
                teilt = false
            }
        },
        enabled = !teilt && audit.len() > 0u,
    ) {
        Text("Kette als Datei teilen")
    }

    ablage?.let { ziel ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (ablageOk) "Gespeichert: $ziel" else ziel,
            style = MaterialTheme.typography.bodySmall,
            color = if (ablageOk) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    if (zeilen.isEmpty()) {
        Text(
            "noch keine Einträge — sie entstehen beim Ausführen einer Funktion oder beim SFD-Ablauf",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val sichtbar = zeilen.takeLast(SICHTBARE_EINTRAEGE)
        if (zeilen.size > sichtbar.size) {
            Text(
                "${zeilen.size - sichtbar.size} ältere Einträge nur im Export",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sichtbar.forEach { zeile ->
            Text(
                text = zeile,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
