package com.visor.tv.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.visor.tv.VisorApp
import com.visor.tv.record.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copia de seguridad de lo que es tuyo dentro de la app: favoritos, progreso, canales recientes,
 * ajustes y grabaciones programadas. No incluye la cuenta (servidor, usuario y contraseña):
 * por seguridad, eso se vuelve a introducir a mano.
 */
@Serializable
data class BackupData(
    val format: Int = 1,
    val created: Long,
    val appVersion: String,
    val favorites: List<FavItem> = emptyList(),
    val progress: List<ProgressEntry> = emptyList(),
    val recents: List<PlayItem> = emptyList(),
    val recordings: List<Recording> = emptyList(),
    /** Ajustes como texto con prefijo de tipo: "s:…", "i:…", "l:…", "b:…", "f:…" */
    val settings: Map<String, String> = emptyMap(),
)

data class BackupResult(val ok: Boolean, val message: String)

class Backup(private val app: VisorApp) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Carpeta propia de la app en el almacenamiento: sobrevive a las actualizaciones y se puede copiar por ADB. */
    private val appFile: File get() = File(app.getExternalFilesDir(null) ?: app.filesDir, FILE_NAME)

    val appFilePath: String get() = appFile.absolutePath

    fun lastBackup(): Long = appFile.takeIf { it.exists() }?.lastModified() ?: 0L

    suspend fun export(): BackupResult = withContext(Dispatchers.IO) {
        try {
            val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
                when (v) {
                    is String -> k to "s:$v"
                    is Int -> k to "i:$v"
                    is Long -> k to "l:$v"
                    is Boolean -> k to "b:$v"
                    is Float -> k to "f:$v"
                    else -> null
                }
            }.toMap()
            val data = BackupData(
                created = System.currentTimeMillis(),
                appVersion = app.updater.current,
                favorites = app.favorites.items.toList(),
                progress = app.progress.entries.toList(),
                recents = app.recents.items.toList(),
                recordings = app.recordings.items.filter { it.status == Recording.STATUS_SCHEDULED },
                settings = prefs,
            )
            val text = json.encodeToString(data)
            appFile.parentFile?.mkdirs()
            appFile.writeText(text)
            val extra = if (saveToDownloads(text)) " y en la carpeta Descargas" else ""
            BackupResult(
                true,
                "Copia guardada (${data.favorites.size} favoritos, ${data.progress.size} a medias, " +
                    "${data.recordings.size} grabaciones programadas) en la carpeta de Visor$extra.",
            )
        } catch (e: Exception) {
            BackupResult(false, "No se pudo guardar la copia: ${e.message}")
        }
    }

    /** Copia extra en Descargas, para tenerla aunque se desinstale la app. Si el sistema no deja, se omite. */
    private fun saveToDownloads(text: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            // Sustituye la copia anterior que hizo esta app
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(FILE_NAME),
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) false else {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                true
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, FILE_NAME).writeText(text)
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun readAny(): String? {
        if (appFile.exists()) return appFile.readText()
        // Copia en Descargas (la que hizo esta app, o una dejada ahí en sistemas antiguos)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(FILE_NAME), null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                        return app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
                if (f.exists()) return f.readText()
            }
        } catch (_: Exception) { }
        return null
    }

    /** Lee la copia y devuelve un resumen, sin aplicar nada todavía. */
    suspend fun peek(): BackupData? = withContext(Dispatchers.IO) {
        try { readAny()?.let { json.decodeFromString<BackupData>(it) } } catch (e: Exception) { null }
    }

    /** Aplica la copia. Después hay que reiniciar la app para que los ajustes se carguen. */
    suspend fun restore(data: BackupData): BackupResult {
        return try {
            withContext(Dispatchers.IO) {
                val ed = app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
                data.settings.forEach { (k, v) ->
                    val value = v.substring(2)
                    when (v.take(2)) {
                        "s:" -> ed.putString(k, value)
                        "i:" -> value.toIntOrNull()?.let { ed.putInt(k, it) }
                        "l:" -> value.toLongOrNull()?.let { ed.putLong(k, it) }
                        "b:" -> ed.putBoolean(k, value == "true")
                        "f:" -> value.toFloatOrNull()?.let { ed.putFloat(k, it) }
                    }
                }
                ed.commit()
            }
            app.favorites.replaceAll(data.favorites)
            app.progress.replaceAll(data.progress)
            data.recents.asReversed().forEach { app.recents.add(it) }
            val now = System.currentTimeMillis()
            data.recordings.filter { it.end > now && app.recordings.get(it.id) == null }.forEach { app.recordings.schedule(it) }
            BackupResult(true, "Copia restaurada. Visor se reinicia para aplicar los ajustes.")
        } catch (e: Exception) {
            BackupResult(false, "No se pudo restaurar: ${e.message}")
        }
    }

    fun restartApp() {
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val FILE_NAME = "visor-backup.json"
        fun dateOf(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es")).format(Date(ms))
    }
}
