package com.visor.tv.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Acceso a la API Xtream (player_api.php) con caché en disco y en memoria.
 * Cada carpeta se descarga solo cuando se abre, y se guarda para que el siguiente
 * arranque sea inmediato.
 */
class Repo(private val ctx: Context, private val http: OkHttpClient, private val vault: SecureStore) {

    var account by mutableStateOf(vault.loadAccount())
        private set

    /** Sube cada vez que se actualiza la lista; las pantallas lo usan como clave para recargar. */
    var version by mutableIntStateOf(0)
        private set

    /** true mientras se está descargando la lista de nuevo. */
    var refreshing by mutableStateOf(false)
        private set

    private val prefs = ctx.getSharedPreferences("repo", Context.MODE_PRIVATE)

    /** Momento de la última actualización completa de la lista (ms), 0 si nunca. */
    val lastRefresh: Long get() = prefs.getLong("lastRefresh", 0L)

    private val mem = ConcurrentHashMap<String, Any>()
    private val epgMem = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()
    private val dir get() = File(ctx.cacheDir, "xt").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun acc(): Account = account ?: throw IOException("Sin cuenta")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(a: Account, params: String) =
        "${a.base}/player_api.php?username=${enc(a.user)}&password=${enc(a.pass)}$params"

    private suspend fun fetch(a: Account, params: String): JsonElement = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl(a, params)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("El servidor respondió ${r.code}")
            val body = r.body?.string() ?: throw IOException("Respuesta vacía")
            json.parseToJsonElement(body)
        }
    }

    private fun cacheFile(params: String): File {
        val a = acc()
        val d = MessageDigest.getInstance("SHA-1").digest((a.base + a.user + params).toByteArray())
        return File(dir, d.joinToString("") { "%02x".format(it) } + ".json")
    }

    /** Pide a la red si la caché ha caducado; si la red falla, usa la caché aunque sea antigua. */
    private suspend fun cached(params: String, ttlMs: Long): JsonElement = withContext(Dispatchers.IO) {
        val f = cacheFile(params)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs
        if (fresh) {
            try { return@withContext json.parseToJsonElement(f.readText()) } catch (_: Exception) { }
        }
        try {
            val el = fetch(acc(), params)
            f.writeText(el.toString())
            el
        } catch (e: Exception) {
            if (f.exists()) json.parseToJsonElement(f.readText()) else throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> memo(key: String, block: suspend () -> T): T {
        mem[key]?.let { return it as T }
        val v = block()
        mem[key] = v
        return v
    }

    // ---------------- Cuenta ----------------

    suspend fun login(a: Account): AccountInfo {
        val info = parseAccount(fetch(a, ""))
        if (!info.active) throw IOException("Cuenta no activa (${info.status.ifBlank { "credenciales incorrectas" }})")
        vault.saveAccount(a)
        account = a
        mem.clear(); epgMem.clear()
        prefs.edit().putLong("lastRefresh", System.currentTimeMillis()).apply()
        return info
    }

    suspend fun accountInfo(): AccountInfo = parseAccount(fetch(acc(), ""))

    private fun parseAccount(el: JsonElement): AccountInfo {
        val u = el.asObj()?.get("user_info").asObj() ?: throw IOException("Respuesta no válida del servidor")
        val exp = u.l("exp_date").takeIf { it > 0 }?.times(1000)
        return AccountInfo(
            active = u.i("auth") == 1 && u.s("status").equals("Active", ignoreCase = true),
            status = u.s("status"),
            expires = exp,
            maxConnections = u.i("max_connections"),
            activeConnections = u.i("active_cons"),
        )
    }

    fun logout() {
        vault.saveAccount(null)
        account = null
        clearCache()
    }

    /**
     * Vuelve a descargar categorías y listas completas, sustituye la caché y avisa a la interfaz.
     * Si falla la red, se queda la lista anterior y no se toca nada.
     */
    suspend fun refreshAll(): Boolean {
        if (refreshing || account == null) return false
        withContext(Dispatchers.Main) { refreshing = true }
        return try {
            withContext(Dispatchers.IO) {
                val a = acc()
                val fresh = FULL_LISTS.associateWith { fetch(a, it) } // todo o nada: si falla una, no se guarda ninguna
                val keep = fresh.keys.map { cacheFile(it) }.toSet()
                fresh.forEach { (params, el) -> cacheFile(params).writeText(el.toString()) }
                // Las carpetas sueltas se vuelven a pedir cuando se abran
                dir.listFiles()?.forEach { f -> if (f !in keep) f.delete() }
            }
            mem.clear(); epgMem.clear()
            prefs.edit().putLong("lastRefresh", System.currentTimeMillis()).apply()
            withContext(Dispatchers.Main) { version++ }
            true
        } catch (e: Exception) {
            false
        } finally {
            withContext(Dispatchers.Main) { refreshing = false }
        }
    }

    fun clearCache() {
        mem.clear(); epgMem.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    // ---------------- Listados ----------------

    private fun categories(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }
        .map { Category(it.s("category_id"), it.s("category_name")) }

    suspend fun liveCategories(): List<Category> = memo("lc") { categories(cached("&action=get_live_categories", TTL_LIST)) }
    suspend fun vodCategories(): List<Category> = memo("vc") { categories(cached("&action=get_vod_categories", TTL_LIST)) }
    suspend fun seriesCategories(): List<Category> = memo("sc") { categories(cached("&action=get_series_categories", TTL_LIST)) }

    private fun parseLive(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }.map {
        LiveStream(it.i("stream_id"), it.i("num"), it.s("name"), it.s("stream_icon"), it.s("category_id"), it.s("epg_channel_id"))
    }

    private fun parseVod(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }.map {
        VodStream(it.i("stream_id"), it.s("name"), it.s("stream_icon"), it.s("rating"),
            it.s("container_extension").ifBlank { "mp4" }, it.s("category_id"), it.l("added"))
    }

    private fun parseSeries(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }.map {
        SeriesItem(it.i("series_id"), it.s("name"), it.s("cover"), it.s("rating"), it.s("plot"), it.s("category_id"),
            it.l("last_modified"))
    }

    suspend fun liveStreams(cat: String): List<LiveStream> = memo("ls:$cat") {
        parseLive(cached("&action=get_live_streams&category_id=${enc(cat)}", TTL_LIST))
    }

    suspend fun vodStreams(cat: String): List<VodStream> = memo("vs:$cat") {
        parseVod(cached("&action=get_vod_streams&category_id=${enc(cat)}", TTL_LIST))
    }

    suspend fun series(cat: String): List<SeriesItem> = memo("ss:$cat") {
        parseSeries(cached("&action=get_series&category_id=${enc(cat)}", TTL_LIST))
    }

    // Listas completas, para el buscador y las novedades. Se descargan una vez y quedan en caché.
    suspend fun allLive(): List<LiveStream> = memo("ls:*") { parseLive(cached("&action=get_live_streams", TTL_LIST)) }
    suspend fun allVod(): List<VodStream> = memo("vs:*") { parseVod(cached("&action=get_vod_streams", TTL_LIST)) }
    suspend fun allSeries(): List<SeriesItem> = memo("ss:*") { parseSeries(cached("&action=get_series", TTL_LIST)) }

    /** Las [n] películas añadidas más recientemente a la lista. */
    suspend fun latestVod(n: Int = 30): List<VodStream> = memo("vs:new") {
        val all = allVod()
        if (all.any { it.added > 0 }) all.sortedByDescending { it.added }.take(n)
        else all.sortedByDescending { it.id }.take(n) // sin fecha: el id suele crecer con cada alta
    }

    /** Las [n] series nuevas o actualizadas más recientemente. */
    suspend fun latestSeries(n: Int = 30): List<SeriesItem> = memo("ss:new") {
        val all = allSeries()
        if (all.any { it.updated > 0 }) all.sortedByDescending { it.updated }.take(n)
        else all.sortedByDescending { it.id }.take(n)
    }

    // ---------------- Fichas ----------------

    private fun firstOf(o: JsonObject, vararg keys: String): String {
        for (k in keys) { val v = o.s(k); if (v.isNotBlank()) return v }
        return ""
    }

    private fun backdrop(o: JsonObject): String {
        val el = o["backdrop_path"]
        return if (el is JsonArray) o.s("backdrop_path").split(", ").firstOrNull().orEmpty() else o.s("backdrop_path")
    }

    suspend fun vodInfo(id: Int): VodInfo = memo("vi:$id") {
        val root = cached("&action=get_vod_info&vod_id=$id", TTL_INFO).asObj() ?: throw IOException("Ficha no disponible")
        val info = root["info"].asObj() ?: JsonObject(emptyMap())
        val data = root["movie_data"].asObj() ?: JsonObject(emptyMap())
        VodInfo(
            name = firstOf(data, "name").ifBlank { firstOf(info, "name", "o_name") },
            plot = firstOf(info, "plot", "description"),
            cast = firstOf(info, "cast", "actors"),
            director = firstOf(info, "director"),
            genre = firstOf(info, "genre"),
            duration = firstOf(info, "duration"),
            year = firstOf(info, "releasedate", "release_date", "year").take(4),
            rating = firstOf(info, "rating"),
            cover = firstOf(info, "movie_image", "cover_big"),
            backdrop = backdrop(info),
            tmdbId = firstOf(info, "tmdb_id", "tmdb"),
            ext = firstOf(data, "container_extension").ifBlank { "mp4" },
        )
    }

    suspend fun seriesInfo(id: Int): SeriesInfo = memo("si:$id") {
        val root = cached("&action=get_series_info&series_id=$id", TTL_INFO).asObj() ?: throw IOException("Ficha no disponible")
        val info = root["info"].asObj() ?: JsonObject(emptyMap())
        val seasons = sortedMapOf<Int, List<Episode>>()
        val epsEl = root["episodes"]
        val groups: List<Pair<String, JsonElement>> = when (epsEl) {
            is JsonObject -> epsEl.entries.map { it.key to it.value }
            is JsonArray -> epsEl.mapIndexed { i, el -> (i + 1).toString() to el }
            else -> emptyList()
        }
        for ((key, arr) in groups) {
            val list = arr.asArr().mapNotNull { it.asObj() }.map { e ->
                val ei = e["info"].asObj() ?: JsonObject(emptyMap())
                Episode(
                    id = e.i("id"),
                    season = e.i("season").takeIf { it > 0 } ?: key.toIntOrNull() ?: 1,
                    num = e.i("episode_num"),
                    title = e.s("title"),
                    ext = e.s("container_extension").ifBlank { "mp4" },
                    plot = firstOf(ei, "plot", "overview"),
                    image = firstOf(ei, "movie_image", "cover_big"),
                    duration = firstOf(ei, "duration"),
                )
            }.sortedBy { it.num }
            if (list.isNotEmpty()) seasons[list.first().season] = list
        }
        SeriesInfo(
            name = firstOf(info, "name"),
            plot = firstOf(info, "plot"),
            cover = firstOf(info, "cover"),
            backdrop = backdrop(info),
            genre = firstOf(info, "genre"),
            year = firstOf(info, "releaseDate", "release_date", "year").take(4),
            rating = firstOf(info, "rating"),
            tmdbId = firstOf(info, "tmdb", "tmdb_id"),
            seasons = seasons,
        )
    }

    // ---------------- EPG ----------------

    /** Guía corta de un canal (próximas horas). Se guarda 10 minutos en memoria. */
    suspend fun shortEpg(streamId: Int, limit: Int = 12): List<EpgEntry> {
        epgMem[streamId]?.let { (t, list) -> if (System.currentTimeMillis() - t < 10 * 60_000) return list }
        val list = try {
            fetch(acc(), "&action=get_short_epg&stream_id=$streamId&limit=$limit").asObj()?.get("epg_listings").asArr()
                .mapNotNull { it.asObj() }
                .map { EpgEntry(b64(it.s("title")), b64(it.s("description")), it.l("start_timestamp") * 1000, it.l("stop_timestamp") * 1000) }
                .filter { it.end > it.start }
                .sortedBy { it.start }
        } catch (e: Exception) {
            emptyList()
        }
        epgMem[streamId] = System.currentTimeMillis() to list
        return list
    }

    // ---------------- URLs (solo en memoria, nunca se guardan ni se registran) ----------------

    private fun p(s: String) = Uri.encode(s)

    fun liveUrl(streamId: Int, ext: String): String {
        val a = acc(); return "${a.base}/live/${p(a.user)}/${p(a.pass)}/$streamId.$ext"
    }

    fun urlFor(item: PlayItem, liveExt: String): String {
        val a = acc()
        return when (item.kind) {
            "live" -> liveUrl(item.id, liveExt)
            "vod" -> "${a.base}/movie/${p(a.user)}/${p(a.pass)}/${item.id}.${item.ext}"
            "episode" -> "${a.base}/series/${p(a.user)}/${p(a.pass)}/${item.id}.${item.ext}"
            else -> Uri.fromFile(File(item.filePath)).toString()
        }
    }

    companion object {
        private val FULL_LISTS = listOf(
            "&action=get_live_categories", "&action=get_vod_categories", "&action=get_series_categories",
            "&action=get_live_streams", "&action=get_vod_streams", "&action=get_series",
        )
        private const val TTL_LIST = 6 * 60 * 60_000L
        private const val TTL_INFO = 24 * 60 * 60_000L
    }
}
