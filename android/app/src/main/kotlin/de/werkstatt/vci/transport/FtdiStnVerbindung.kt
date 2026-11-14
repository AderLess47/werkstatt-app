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
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * USB-Weg für Klasse-1-Dongles mit FTDI-Wandler — der Kabelweg zu OBDLink EX/SX.
 *
 * Im Unterschied zu [UsbDongleVerbindung] wird hier **kein** Bulk-Endpunkt roh
 * gelesen: der FTDI-Chip verlangt Baudrate und Leitungsparameter, und seine
 * Datenrahmen tragen Statusbytes. Das erledigt `usb-serial-for-android`
 * (MIT-Lizenz); die App sieht einen Bytestrom und legt ihre
 * [UsbSerialStrecke] darüber.
 *
 * **Begrenzt auf STN-Geräte** — zweifach:
 *
 * 1. Es werden nur Geräte mit FTDI-VID (0x0403) angesprochen. CP210x, CH340 und
 *    PL2303 sind die Chips der ausgeschlossenen ELM327-Clones und bleiben es.
 * 2. Nach dem Öffnen muss [StnKennung] die Firmware-Kennung (`STI`) bestätigen.
 *    Ein ELM327-Clone am selben Chip fällt hier durch und wird abgelehnt, nicht
 *    halb unterstützt.
 *
 * Was das nicht leistet: ein Clone, der `STI` nachahmt, käme durch die Prüfung.
 * Die Grenze bleibt eine Produktentscheidung (Dongle-Matrix §1), keine
 * technische Garantie.
 */
class FtdiStnVerbindung(context: Context) : AutoCloseable {

    /** Ergebnis eines Verbindungsversuchs — ohne Ausnahme, damit die Oberfläche es anzeigen kann. */
    sealed interface Ergebnis {
        data class Bereit(val kanal: StnKanal, val kennung: String, val geraet: String) : Ergebnis
        data class Abgelehnt(val text: String) : Ergebnis
    }

    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager

    private var offenerPort: UsbSerialPort? = null
    private var strecke: UsbSerialStrecke? = null
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

    suspend fun oeffnen(baudrate: Int = BAUDRATE): Ergebnis = withContext(Dispatchers.IO) {
        val treiber = treiber()
            ?: return@withContext Ergebnis.Abgelehnt(
                "kein FTDI-Dongle gefunden — Kabel, USB-Host und Stromversorgung prüfen"
            )
        val geraet = treiber.device
        val name = beschreibung(geraet)

        if (!usb.hasPermission(geraet) && !freigabeAnfordern(geraet)) {
            return@withContext Ergebnis.Abgelehnt("$name · Zugriff verweigert")
        }

        val verbindung = usb.openDevice(geraet)
            ?: return@withContext Ergebnis.Abgelehnt("$name · ließ sich nicht öffnen — von einer anderen App belegt?")
        val port = treiber.ports.firstOrNull()
            ?: run {
                verbindung.close()
                return@withContext Ergebnis.Abgelehnt("$name · keine serielle Schnittstelle")
            }

        try {
            port.open(verbindung)
            port.setParameters(baudrate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
        } catch (e: Exception) {
            runCatching { port.close() }
            verbindung.close()
            return@withContext Ergebnis.Abgelehnt(
                "$name · Schnittstelle öffnet nicht: ${e.message ?: e.toString()}"
            )
        }

        schliessePort()
        offenerPort = port
        val neueStrecke = UsbSerialStrecke(port)
        strecke = neueStrecke

        val kennung = StnKennung.erkenne(neueStrecke)
        if (kennung == null) {
            schliessePort()
            return@withContext Ergebnis.Abgelehnt(
                "$name · kein STN-Gerät — nur Geräte mit STN-Firmware werden angesprochen"
            )
        }

        Ergebnis.Bereit(StnKanal(neueStrecke), kennung, name)
    }

    /** FTDI-Gerät mit serieller Schnittstelle, erstes nach Gerätenamen. */
    private fun treiber(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber()
            .findAllDrivers(usb)
            .filter { it.device.vendorId == FTDI_VID }
            .sortedBy { it.device.deviceName }
            .firstOrNull()

    private fun beschreibung(geraet: UsbDevice): String =
        "VID %04X:PID %04X".format(geraet.vendorId, geraet.productId) +
            (geraet.productName?.let { " · $it" } ?: "")

    private suspend fun freigabeAnfordern(geraet: UsbDevice): Boolean {
        val antwort = CompletableDeferred<Boolean>()
        wartendeFreigabe = antwort

        // Ab Android 12 muss der PendingIntent veränderlich sein, sonst füllt das
        // System EXTRA_PERMISSION_GRANTED nicht aus (wie in UsbDongleVerbindung).
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(AKTION).setPackage(app.packageName)
        usb.requestPermission(geraet, PendingIntent.getBroadcast(app, 0, intent, flags))

        val erlaubt = withTimeoutOrNull(30_000) { antwort.await() } ?: false
        wartendeFreigabe = null
        return erlaubt
    }

    private fun schliessePort() {
        strecke?.abgebrochen()
        strecke = null
        runCatching { offenerPort?.close() }
        offenerPort = null
    }

    override fun close() {
        schliessePort()
        runCatching { app.unregisterReceiver(empfaenger) }
    }

    companion object {
        const val AKTION = "de.werkstatt.vci.USB_FREIGABE_STN"

        /** FTDI-VID. Bewusst nur dieser — CP210x/CH340 sind die Chips der ausgeschlossenen Clones. */
        private const val FTDI_VID = 0x0403

        /** Standard der OBDLink-EX/SX-Firmware; am Gerät zu bestätigen. */
        private const val BAUDRATE = 115_200
    }
}
