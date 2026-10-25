package com.visor.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Un programa de la guía con el canal (id de EPG) al que pertenece. */
data class EpgHit(val epgId: String, val entry: EpgEntry)

/**
 * Guía completa del proveedor (XMLTV): se descarga entera una vez al día y se guarda en una base de datos
 * del aparato. Así la guía abarca varios días, se puede buscar por título y no hay que pedirla canal a canal.
 * Si un canal no aparece aquí, se sigue usando la guía corta de la API.
 */
class EpgStore(private val ctx: Context, private val http: OkHttpClient) {
    private val prefs = ctx.getSharedPreferences("epg", Context.MODE_PRIVATE)
    private val db by lazy { Helper(ctx).writableDatabase }
    private val lock = Mutex()

    var updating by mutableStateOf(false)
        private set
    /** Mensaje del último intento (para Ajustes). */
    var status by mutableStateOf<String?>(null)
        private set
    /** Cambia al terminar una descarga, para que la guía se vuelva a pintar. */
    var version by mutableIntStateOf(0)
        private set

    val lastUpdate: Long get() = prefs.getLong("last", 0L)
    val programmes: Int get() = prefs.getInt("count", 0)
    val ready: Boolean get() = programmes > 0

    /** Descarga si han pasado más de [maxAgeH] horas desde la última vez. */
    suspend fun refreshIfOld(repo: Repo, maxAgeH: Int = 12) {
        if (System.currentTimeMillis() - lastUpdate >= maxAgeH * 3_600_000L) refresh(repo)
    }

    suspend fun refresh(repo: Repo): Boolean = lock.withLock {
        updating = true
        status = "Descargando la guía…"
        try {
            // Solo los canales de tu lista: la guía de algunos proveedores trae miles que no usas
            val wanted = try { repo.allLive().map { it.epgId.lowercase() }.filter { it.isNotBlank() }.toSet() } catch (e: Exception) { emptySet() }
            val n = withContext(Dispatchers.IO) { download(repo.xmltvUrl(), wanted) }
            if (n == 0) {
                status = "Tu proveedor no ha devuelto guía completa. Se usa la guía corta canal a canal."
                false
            } else {
                prefs.edit().putLong("last", System.currentTimeMillis()).putInt("count", n).apply()
                status = null
                version++
                true
            }
        } catch (e: Exception) {
            status = "No se pudo descargar la guía (${e.message ?: "error"}). Se usa la guía corta."
            false
        } finally {
            updating = false
        }
    }

    private fun download(url: String, wanted: Set<String>): Int {
        val req = Request.Builder().url(url).build()
        http.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Respuesta vacía")
            return parseInto(maybeGunzip(body.byteStream()), wanted)
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val b = BufferedInputStream(input, 64 * 1024)
        b.mark(2)
        val m1 = b.read(); val m2 = b.read()
        b.reset()
        return if (m1 == 0x1f && m2 == 0x8b) GZIPInputStream(b, 64 * 1024) else b
    }

    /** Lee el XMLTV y lo guarda en una tabla nueva; solo sustituye a la anterior si ha ido bien. */
    private fun parseInto(input: InputStream, wanted: Set<String>): Int {
        val now = System.currentTimeMillis()
        val from = now - 6 * 3_600_000L
        val to = now + 4 * 86_400_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val fmtNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(s: String?): Long {
            if (s.isNullOrBlank()) return 0L
            val t = s.trim()
            return try {
                if (t.length > 14) fmt.parse(t.substring(0, 14) + " " + t.substring(14).trim())?.time ?: 0L
                else fmtNoTz.parse(t.take(14))?.time ?: 0L
            } catch (e: Exception) { 0L }
        }

        db.execSQL("DROP TABLE IF EXISTS prog_new")
        db.execSQL("CREATE TABLE prog_new (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, norm TEXT NOT NULL, descr TEXT NOT NULL)")
        val ins = db.compileStatement("INSERT INTO prog_new (ch, start, stop, title, norm, descr) VALUES (?, ?, ?, ?, ?, ?)")
        var count = 0
        var inBatch = 0
        db.beginTransaction()
        try {
            val xp = Xml.newPullParser()
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            xp.setInput(input, null)
            var ch = ""; var start = 0L; var stop = 0L; var title = ""; var desc = ""
            var inProg = false
            var ev = xp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (xp.name) {
                        "programme" -> {
                            inProg = true
                            ch = xp.getAttributeValue(null, "channel").orEmpty()
                            start = time(xp.getAttributeValue(null, "start"))
                            stop = time(xp.getAttributeValue(null, "stop"))
                            title = ""; desc = ""
                        }
                        "title" -> if (inProg && title.isEmpty()) title = xp.nextText().orEmpty().trim()
                        "desc" -> if (inProg && desc.isEmpty()) desc = xp.nextText().orEmpty().trim()
                    }
                    XmlPullParser.END_TAG -> if (xp.name == "programme") {
                        inProg = false
                        val key = ch.lowercase()
                        if (key.isNotBlank() && stop > start && stop > from && start < to && title.isNotBlank() &&
                            (wanted.isEmpty() || key in wanted)
                        ) {
                            ins.bindString(1, key)
                            ins.bindLong(2, start)
                            ins.bindLong(3, stop)
                            ins.bindString(4, title)
                            ins.bindString(5, normalizeText(title))
                            ins.bindString(6, desc.take(400))
                            ins.executeInsert()
                            count++
                            if (++inBatch >= 5000) {
                                db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction(); inBatch = 0
                            }
                        }
                    }
                }
                ev = xp.next()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (count == 0) {
            db.execSQL("DROP TABLE IF EXISTS prog_new")
            return 0
        }
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS prog")
            db.execSQL("ALTER TABLE prog_new RENAME TO prog")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_prog_ch ON prog (ch, start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_prog_start ON prog (start)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    private fun hasTable(): Boolean = ready

    /** Programas de un canal que se solapan con [from, to). */
    suspend fun range(epgId: String, from: Long, to: Long): List<EpgEntry> = withContext(Dispatchers.IO) {
        if (!hasTable() || epgId.isBlank()) return@withContext emptyList()
        query("SELECT title, descr, start, stop FROM prog WHERE ch = ? AND stop > ? AND start < ? ORDER BY start",
            arrayOf(epgId.lowercase(), from.toString(), to.toString()))
    }

    /** Lo que está en emisión y lo siguiente de un canal. */
    suspend fun upcoming(epgId: String, limit: Int): List<EpgEntry> = withContext(Dispatchers.IO) {
        if (!hasTable() || epgId.isBlank()) return@withContext emptyList()
        query("SELECT title, descr, start, stop FROM prog WHERE ch = ? AND stop > ? ORDER BY start LIMIT $limit",
            arrayOf(epgId.lowercase(), System.currentTimeMillis().toString()))
    }

    /** Busca programas por título (todas las palabras, sin tildes), de ahora en adelante. */
    suspend fun search(text: String, limit: Int = 200): List<EpgHit> = withContext(Dispatchers.IO) {
        val words = normalizeText(text).split(' ').filter { it.length >= 2 }.take(5)
        if (!hasTable() || words.isEmpty()) return@withContext emptyList()
        val where = words.joinToString(" AND ") { "norm LIKE ?" }
        val args = words.map { "%$it%" } + System.currentTimeMillis().toString()
        val out = mutableListOf<EpgHit>()
        try {
            db.rawQuery("SELECT ch, title, descr, start, stop FROM prog WHERE $where AND stop > ? ORDER BY start LIMIT $limit", args.toTypedArray()).use { c ->
                while (c.moveToNext()) out += EpgHit(c.getString(0), EpgEntry(c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)))
            }
        } catch (e: Exception) { }
        out
    }

    private fun query(sql: String, args: Array<String>): List<EpgEntry> = try {
        db.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(EpgEntry(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))) }
        }
    } catch (e: Exception) { emptyList() }

    /** Al cerrar sesión o borrar la caché. */
    fun clear() {
        try { db.execSQL("DROP TABLE IF EXISTS prog") } catch (_: Exception) { }
        prefs.edit().clear().apply()
        version++
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 1) {
        // Con WAL, la guía se puede leer mientras se está descargando la nueva
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {}
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}

/** Minúsculas y sin tildes, para buscar sin preocuparse de cómo está escrito. */
fun normalizeText(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
