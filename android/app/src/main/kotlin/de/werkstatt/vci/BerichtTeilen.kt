package de.werkstatt.vci

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore

/**
 * Legt den Bericht als Datei ab und öffnet den Teilen-Dialog.
 *
 * Die Datei landet in `Downloads/Werkstatt VCI/`, also dort, wo die Werkstatt
 * sie auch ohne Teilen-Dialog findet (Dateien-App, USB-Kabel, Mail-Anhang von
 * Hand). Von dort geht sie über den System-Dialog in Mail, Messenger oder
 * Ticketsystem.
 *
 * Warum `MediaStore` und kein `FileProvider`: ab Android 10 (minSdk 29) ist
 * `MediaStore.Downloads` der vorgesehene Weg für Dateien, die der Benutzer
 * behalten soll, und liefert gleich eine `content://`-Adresse, die andere Apps
 * mit Leseerlaubnis öffnen dürfen. Das spart einen Provider im Manifest und
 * eine eigene Pfadfreigabe — weniger bewegliche Teile für denselben Zweck.
 */
object BerichtTeilen {

    /** Unterordner in `Downloads`, damit die Berichte nicht zwischen Fotos liegen. */
    const val ORDNER = "Werkstatt VCI"

    /** Wo die Datei nach dem Teilen liegt — für die Rückmeldung in der Oberfläche. */
    fun ablage(name: String): String = "Downloads/$ORDNER/$name"

    /**
     * Schreibt [inhalt] als [name] und startet den Teilen-Dialog.
     *
     * Rückgabe: `null`, wenn die Datei geschrieben wurde (auch wenn der Dialog
     * abgebrochen wird), sonst eine Meldung im Klartext für die Oberfläche.
     * Blockiert — gehört auf einen Hintergrund-Thread.
     */
    fun teilen(context: Context, name: String, inhalt: String): String? {
        val aufloeser = context.contentResolver

        val eintrag = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ORDNER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val ziel = try {
            aufloeser.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, eintrag)
        } catch (e: Exception) {
            null
        } ?: return "Die Datei konnte nicht angelegt werden."

        val geschrieben = try {
            aufloeser.openOutputStream(ziel)?.use { strom ->
                strom.write(inhalt.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        } catch (e: Exception) {
            false
        }

        // IS_PENDING muss in jedem Fall zurückgesetzt werden: solange es steht,
        // sieht keine andere App die Datei — auch der Mail-Anhang nicht.
        val fertig = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        try {
            aufloeser.update(ziel, fertig, null, null)
        } catch (e: Exception) {
            // Nicht schlimm: die Datei ist geschrieben, nur noch nicht sichtbar.
        }

        if (!geschrieben) {
            // Halbe Dateien liegen lassen wäre schlimmer als nichts.
            try {
                aufloeser.delete(ziel, null, null)
            } catch (e: Exception) {
                // dann bleibt der leere Eintrag stehen
            }
            return "Die Datei konnte nicht geschrieben werden."
        }

        val teilen = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, ziel)
            putExtra(Intent.EXTRA_SUBJECT, "VCI-Bericht")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val auswahl = Intent.createChooser(teilen, "VCI-Bericht teilen").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(auswahl)
            null
        } catch (e: Exception) {
            // Die Datei liegt trotzdem in Downloads — der Weg von Hand bleibt.
            "Die Datei liegt in ${ablage(name)}, aber es wurde keine App zum Teilen gefunden. ${e.message}"
        }
    }
}
