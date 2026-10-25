package com.visor.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.visor.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

/**
 * Comprueba las releases públicas del repositorio en GitHub, descarga el APK nuevo y abre el instalador.
 * El APK está firmado con la misma clave, así que se instala encima y se conservan sesión y favoritos.
 */
class Updater(private val ctx: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    var available by mutableStateOf<Release?>(null)
        private set
    var progress by mutableFloatStateOf(-1f) // -1 = sin descarga en curso
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** true = hay que enseñar la ventana de actualización. */
    var prompt by mutableStateOf(false)

    val current: String get() = BuildConfig.VERSION_NAME

    /** Versión que el usuario dijo "más tarde": no se vuelve a ofrecer sola hasta que salga otra. */
    var skipped: String?
        get() = prefs.getString("skipped", null)
        set(v) { prefs.edit().putString("skipped", v).apply() }

    /** [manual] = pedido desde Ajustes: avisa aunque antes se dijera "más tarde". */
    suspend fun check(manual: Boolean = false): Release? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = json.parseToJsonElement(r.body?.string() ?: return@withContext null).asObj() ?: return@withContext null
                val tag = o.s("tag_name").removePrefix("v").removePrefix("V").replace('_', '.')
                val asset = o["assets"].asArr().mapNotNull { it.asObj() }.firstOrNull { it.s("name").endsWith(".apk") }
                    ?: return@withContext null
                val rel = Release(tag, o.s("body"), asset.s("browser_download_url"), asset.l("size"))
                if (isNewer(tag, current)) {
                    withContext(Dispatchers.Main) {
                        available = rel
                        if (manual || rel.version != skipped) prompt = true
                    }
                    rel
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Descarga el APK y abre el instalador del sistema. */
    suspend fun downloadAndInstall(rel: Release) {
        error = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            // Fire OS pide permitir que Visor instale apps; se abre el ajuste directamente
            try {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
            error = "Permite que Visor instale apps (Opciones para desarrolladores › Instalar apps desconocidas › Visor) y vuelve a pulsar Actualizar."
            return
        }
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "visor-${rel.version}.apk")
                http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Descarga fallida (${r.code})")
                    val body = r.body ?: throw IOException("Respuesta vacía")
                    val total = body.contentLength().takeIf { it > 0 } ?: rel.sizeBytes
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) withContext(Dispatchers.Main) { progress = done.toFloat() / total }
                            }
                        }
                    }
                }
                out
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = "No se pudo descargar: ${e.message}" }
                null
            }
        }
        progress = -1f
        if (file == null) return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            error = "No se pudo abrir el instalador: ${e.message}"
        }
    }

    fun dismiss(rel: Release) {
        skipped = rel.version
        prompt = false
        error = null
    }

    companion object {
        const val REPO = "aguado2323-rgb/visor-iptv"

        /** Compara versiones "0.10.2" > "0.9.7" número a número. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
