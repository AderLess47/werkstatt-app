package de.werkstatt.vci.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import de.werkstatt.vci.Befund
import de.werkstatt.vci.DonglePruefung
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Findet den Dongle, holt die USB-Freigabe und öffnet den Transport.
 *
 * Die USB-Freigabe erteilt Android per Dialog und antwortet über einen
 * Broadcast — deshalb der Empfänger und die wartende Koroutine. Ohne Freigabe
 * gibt es kein `openDevice`, und ohne offenes Gerät keinen Test.
 */
class UsbDongleVerbindung(context: Context) : AutoCloseable {

    /** Ergebnis eines Öffnungsversuchs — dieselbe Form wie beim Kabelweg. */
    sealed interface Ergebnis {
        data class Bereit(val transport: UsbCanTransport, val kennung: String) : Ergebnis
        data class Abgelehnt(val text: String) : Ergebnis
    }

    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private var transport: UsbCanTransport? = null
    private var wartendeFreigabe: CompletableDeferred<Boolean>? = null

    private val empfaenger = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AKTION) return
            val erlaubt = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            wartendeFreigabe?.complete(erlaubt)
            wartendeFreigabe = null
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            empfaenger,
            IntentFilter(AKTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    suspend fun pruefen(): List<Befund> = withContext(Dispatchers.IO) {
        val device = UsbCanTransport.kandidat(usb)
            ?: return@withContext listOf(
                Befund(
                    "Dongle",
                    "kein USB-Dongle gefunden — Kabel, USB-Host und Stromversorgung prüfen",
                    ok = false
                )
            )

        val kopf = Befund("Dongle", UsbCanTransport.beschreibung(device), ok = true)

        if (!usb.hasPermission(device) && !freigabeAnfordern(device)) {
            return@withContext listOf(kopf.copy(text = kopf.text + " · Zugriff verweigert", ok = false))
        }

        val geoeffnet = UsbCanTransport.oeffne(usb, device)
            ?: return@withContext listOf(
                kopf,
                Befund("Dongle", "ließ sich nicht öffnen — von einer anderen App belegt?", ok = false)
            )

        transport?.close()
        transport = geoeffnet
        listOf(kopf) + DonglePruefung.pruefe(geoeffnet)
    }

    /**
     * Öffnet nur den Transport und gibt ihn heraus — ohne Prüflauf.
     *
     * Für den selbstständigen Weg-A-Abschnitt am Bildschirm: dort wird zuerst
     * geöffnet und danach ein Dienst gelesen. `pruefen()` bleibt daneben
     * bestehen, weil es die Bordspannung mitnimmt.
     */
    suspend fun oeffnen(): Ergebnis = withContext(Dispatchers.IO) {
        val device = UsbCanTransport.kandidat(usb)
            ?: return@withContext Ergebnis.Abgelehnt(
                "kein USB-Dongle gefunden — Kabel, USB-Host und Stromversorgung prüfen"
            )

        if (!usb.hasPermission(device) && !freigabeAnfordern(device)) {
            return@withContext Ergebnis.Abgelehnt(
                "Zugriff verweigert — im Dialog „Zulassen“ wählen oder die App neu starten"
            )
        }

        val geoeffnet = UsbCanTransport.oeffne(usb, device)
            ?: return@withContext Ergebnis.Abgelehnt(
                "ließ sich nicht öffnen — von einer anderen App belegt?"
            )

        // Ein zweites Öffnen darf das alte Gerät nicht offen lassen.
        transport?.close()
        transport = geoeffnet
        Ergebnis.Bereit(geoeffnet, UsbCanTransport.beschreibung(device))
    }

    private suspend fun freigabeAnfordern(device: UsbDevice): Boolean {
        val antwort = CompletableDeferred<Boolean>()
        wartendeFreigabe = antwort

        // Ab Android 12 muss der PendingIntent als veränderlich markiert sein,
        // sonst füllt das System EXTRA_PERMISSION_GRANTED nicht aus.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(AKTION).setPackage(app.packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags))

        val erlaubt = withTimeoutOrNull(30_000) { antwort.await() } ?: false
        wartendeFreigabe = null
        return erlaubt
    }

    override fun close() {
        transport?.close()
        transport = null
        runCatching { app.unregisterReceiver(empfaenger) }
    }

    companion object {
        const val AKTION = "de.werkstatt.vci.USB_FREIGABE"
    }
}
