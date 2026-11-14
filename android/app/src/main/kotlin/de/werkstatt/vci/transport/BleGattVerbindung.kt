package de.werkstatt.vci.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import de.werkstatt.vci.Befund
import de.werkstatt.vci.DonglePruefung
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Findet einen BLE-Dongle, verbindet über GATT und gibt einen [BleCanTransport].
 *
 * Standardprofil ist der Nordic UART Service (NUS) — die verbreitetste
 * „transparente serielle Strecke" über BLE. Ein Dongle, der unser Drahtformat
 * spricht, wird damit ohne Anpassung nutzbar; andere Profile lassen sich über
 * die UUIDs im Konstruktor setzen.
 *
 * Der Scan filtert auf die Dienst-UUID des Profils. Fremde BLE-Geräte in der
 * Werkstatt (Kopfhörer, Sensoren) werden dadurch nicht angezeigt.
 */
class BleGattVerbindung(
    context: Context,
    private val dienst: UUID = NUS_DIENST,
    private val rxCharakteristik: UUID = NUS_RX,
    private val txCharakteristik: UUID = NUS_TX,
) : AutoCloseable {

    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)

    private var gatt: BluetoothGatt? = null
    private var transport: BleCanTransport? = null
    private var schreibQuittung: ArrayBlockingQueue<Boolean>? = null
    private var schreibSperre = Any()

    @SuppressLint("MissingPermission") // Aufrufer prüft die Rechte in benoetigteRechte()
    suspend fun pruefen(): List<Befund> = withContext(Dispatchers.IO) {
        val adapter = manager?.adapter
            ?: return@withContext listOf(Befund("Bluetooth", "kein Bluetooth-Adapter", ok = false))
        if (!adapter.isEnabled) {
            return@withContext listOf(Befund("Bluetooth", "aus — in den Einstellungen einschalten", ok = false))
        }

        val geraet = scanne(adapter)
            ?: return@withContext listOf(
                Befund(
                    "Bluetooth",
                    "kein Dongle gefunden — eingeschaltet, in Reichweite, bereits verbunden?",
                    ok = false
                )
            )

        val kopf = Befund(
            "Bluetooth",
            (geraet.name ?: "ohne Namen") + " · " + geraet.address,
            ok = true
        )

        val neuerTransport = BleCanTransport { bytes -> schreibeBlockierend(bytes) }
        transport = neuerTransport

        if (!verbinde(geraet, neuerTransport)) {
            neuerTransport.abgebrochen("Verbindung fehlgeschlagen")
            return@withContext listOf(kopf, Befund("Bluetooth", "Verbindung oder Dienst-Suche fehlgeschlagen", ok = false))
        }

        listOf(kopf) + DonglePruefung.pruefe(neuerTransport)
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanne(adapter: BluetoothAdapter): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val treffer = CompletableDeferred<BluetoothDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dienste = result.scanRecord?.serviceUuids ?: return
                if (dienste.any { it.uuid == dienst } && result.device != null) {
                    if (!treffer.isCompleted) treffer.complete(result.device)
                    scanner.stopScan(this)
                }
            }
        }

        val einstellungen = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, einstellungen, callback)
        val geraet = withTimeoutOrNull(SCAN_TIMEOUT_MS) { treffer.await() }
        runCatching { scanner.stopScan(callback) }
        return geraet
    }

    @SuppressLint("MissingPermission")
    private suspend fun verbinde(geraet: BluetoothDevice, ziel: BleCanTransport): Boolean {
        val bereit = CompletableDeferred<Boolean>()

        val rueckruf = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, neuerZustand: Int) {
                if (neuerZustand == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (neuerZustand == BluetoothProfile.STATE_DISCONNECTED) {
                    ziel.abgebrochen("Dongle getrennt")
                    if (!bereit.isCompleted) bereit.complete(false)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val dienstGefunden = g.getService(dienst)
                val rx = dienstGefunden?.getCharacteristic(rxCharakteristik)
                val tx = dienstGefunden?.getCharacteristic(txCharakteristik)
                if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                    if (!bereit.isCompleted) bereit.complete(false)
                    return
                }
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
                if (!bereit.isCompleted) bereit.complete(true)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                charakteristik: BluetoothGattCharacteristic,
                status: Int
            ) {
                schreibQuittung?.offer(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Ältere Android-Versionen rufen diese Überladung auf")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                charakteristik: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val bytes = charakteristik.value ?: return
                if (charakteristik.uuid == txCharakteristik) ziel.empfangen(bytes)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                charakteristik: BluetoothGattCharacteristic,
                wert: ByteArray
            ) {
                if (charakteristik.uuid == txCharakteristik) ziel.empfangen(wert)
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            geraet.connectGatt(app, false, rueckruf, BluetoothDevice.TRANSPORT_LE)
        } else {
            geraet.connectGatt(app, false, rueckruf)
        }

        val erfolg = withTimeoutOrNull(VERBINDUNGS_TIMEOUT_MS) { bereit.await() } ?: false
        if (!erfolg) {
            runCatching { gatt?.close() }
            gatt = null
        }
        return erfolg
    }

    /**
     * GATT erlaubt nur eine Schreiboperation zur Zeit: hier wird gewartet, bis
     * das System die vorige quittiert hat, sonst gehen Rahmen verloren.
     */
    private fun schreibeBlockierend(bytes: ByteArray): Boolean = synchronized(schreibSperre) {
        val aktiverGatt = gatt ?: return false
        val tx = aktiverGatt.getService(dienst)?.getCharacteristic(rxCharakteristik) ?: return false

        for (stueck in bytes.toList().chunked(STANDARD_MTU_NUTZLAST)) {
            val quittung = ArrayBlockingQueue<Boolean>(1)
            schreibQuittung = quittung
            @Suppress("DEPRECATION")
            run {
                tx.value = stueck.toByteArray()
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!aktiverGatt.writeCharacteristic(tx)) {
                    schreibQuittung = null
                    return false
                }
            }
            val erfolg = quittung.poll(SCHREIB_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: false
            schreibQuittung = null
            if (!erfolg) return false
        }
        return true
    }

    override fun close() {
        transport?.schliesse()
        transport = null
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        val NUS_DIENST: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val VERBINDUNGS_TIMEOUT_MS = 15_000L
        private const val SCHREIB_TIMEOUT_MS = 1000L
        private const val STANDARD_MTU_NUTZLAST = 20

        /** Rechte, die für Scan und Verbindung nötig sind — je nach Android-Version. */
        fun benoetigteRechte(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun rechteVorhanden(context: Context): Boolean =
            benoetigteRechte().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) == PackageManager.PERMISSION_GRANTED)
    }
}
