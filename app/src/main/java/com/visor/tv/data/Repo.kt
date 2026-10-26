@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.visor.tv.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val epgMem = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()
    private val dir get() = File(ctx.cacheDir, "xt").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun acc(): Account = account ?: throw IOException("Sin cuenta")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(a: Account, params: String) =
        "${a.base}/player_api.php?username=${enc(a.user)}&password=${enc(a.pass)}$params"

    /** Petición pequeña (cuenta, guía corta…): se lee directamente del flujo, sin pasar por un texto gigante. */
    private suspend fun fetch(a: Account, params: String): JsonElement = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl(a, params)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("El servidor respondió ${r.code}")
            val body = r.body ?: throw IOException("Respuesta vacía")
            body.byteStream().buffered(64 * 1024).use { json.decodeFromStream(JsonElement.serializer(), it) }
        }
    }

    /**
     * Descarga directamente a un fichero temporal (sin cargar la respuesta entera en memoria) y comprueba
     * que parece JSON completo. Devuelve el temporal; quien llama decide si lo da por bueno.
     */
    private fun downloadPart(a: Account, params: String, target: File): File {
        val tmp = File(target.path + ".part")
        http.newCall(Request.Builder().url(apiUrl(a, params)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("El servidor respondió ${r.code}")
            val body = r.body ?: throw IOException("Respuesta vacía")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
        }
        if (!looksLikeJson(tmp)) { tmp.delete(); throw IOException("Respuesta no válida del servidor") }
        return tmp
    }

    /** Primer y último carácter útil: [ … ] o { … }. Barato y evita guardar respuestas cortadas. */
    private fun looksLikeJson(f: File): Boolean {
        if (f.length() < 2) return false
        java.io.RandomAccessFile(f, "r").use { raf ->
            fun at(pos: Long): Int { raf.seek(pos); return raf.read() }
            var i = 0L
            var first = at(i)
            while (first != -1 && Character.isWhitespace(first) && i < f.length() - 1) first = at(++i)
            var j = f.length() - 1
            var last = at(j)
            while (last != -1 && Character.isWhitespace(last) && j > 0) last = at(--j)
            return (first == '['.code && last == ']'.code) || (first == '{'.code && last == '}'.code)
        }
    }

    private fun parseFile(f: File): JsonElement =
        f.inputStream().buffered(64 * 1024).use { json.decodeFromStream(JsonElement.serializer(), it) }

    private fun moveOver(from: File, to: File) {
        if (!from.renameTo(to)) { to.delete(); if (!from.renameTo(to)) throw IOException("No se pudo guardar la caché") }
    }

    private fun cacheFile(params: String): File {
        val a = acc()
        val d = MessageDigest.getInstance("SHA-1").digest((a.base + a.user + params).toByteArray())
        return File(dir, d.joinToString("") { "%02x".format(it) } + ".json")
    }

    /**
     * Pide a la red si la caché ha caducado; si la red falla, usa la caché aunque sea antigua.
     * Las listas completas no caducan solas: las renueva [refreshAll] según Ajustes, así abrir la app
     * nunca espera a una descarga grande.
     */
    private suspend fun cached(params: String, ttlMs: Long): JsonElement = withContext(Dispatchers.IO) {
        val f = cacheFile(params)
        val ttl = if (params in FULL_LISTS) Long.MAX_VALUE else ttlMs
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < ttl
        if (fresh) {
            try { return@withContext parseFile(f) } catch (_: Exception) { }
        }
        try {
            val tmp = downloadPart(acc(), params, f)
            val el = try { parseFile(tmp) } catch (e: Exception) { tmp.delete(); throw IOException("Respuesta no válida del servidor") }
            moveOver(tmp, f)
            el
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (f.exists()) parseFile(f) else throw e
        }
    }

    /**
     * Lista grande guardada también en formato compacto (solo los campos que usa la app). Leerla al abrir
     * la app es mucho más rápido y gasta menos memoria que volver a interpretar la respuesta del proveedor.
     */
    private suspend fun <T> compact(params: String, ser: KSerializer<T>, parse: (JsonElement) -> List<T>): List<T> =
        withContext(Dispatchers.IO) {
            val raw = cacheFile(params)
            val c = File(raw.path.removeSuffix(".json") + ".c.json")
            val listSer = ListSerializer(ser)
            val usable = c.exists() && (!raw.exists() || raw.lastModified() <= c.lastModified())
            if (usable) {
                try { return@withContext c.inputStream().buffered(64 * 1024).use { json.decodeFromStream(listSer, it) } } catch (_: Exception) { c.delete() }
            }
            val el = cached(params, TTL_LIST)
            val list = withContext(Dispatchers.Default) { parse(el) }
            try {
                val tmp = File(c.path + ".part")
                tmp.outputStream().buffered(64 * 1024).use { json.encodeToStream(listSer, list, it) }
                moveOver(tmp, c)
            } catch (_: Exception) { }
            list
        }

    /** Hay lista completa a mano (en memoria o en la caché compacta): las carpetas salen de ella sin ir a la red. */
    private fun hasFull(key: String, params: String): Boolean =
        mem.containsKey(key) || File(cacheFile(params).path.removeSuffix(".json") + ".c.json").exists()

    /** Calcula una vez y guarda en memoria. Si dos pantallas lo piden a la vez, la segunda espera a la primera. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> memo(key: String, block: suspend () -> T): T {
        mem[key]?.let { return it as T }
        return locks.getOrPut(key) { Mutex() }.withLock {
            (mem[key] as T?) ?: block().also { mem[key] = it }
        }
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
                // Todo o nada: se descarga cada lista a un temporal, una a una (poca memoria),
                // y solo si han llegado todas bien sustituyen a las anteriores.
                val parts = mutableListOf<Pair<File, File>>()
                try {
                    for (params in FULL_LISTS) {
                        val target = cacheFile(params)
                        parts += downloadPart(a, params, target) to target
                    }
                } catch (e: Exception) {
                    parts.forEach { it.first.delete() }
                    throw e
                }
                parts.forEach { (tmp, target) -> moveOver(tmp, target) }
                val keep = parts.map { it.second }.toSet()
                // Las carpetas sueltas, fichas y versiones compactas se vuelven a crear cuando hagan falta
                dir.listFiles()?.forEach { f -> if (f !in keep) f.delete() }
                mem.clear(); epgMem.clear()
                // Se preparan ya las listas compactas, para que al recargar las pantallas todo esté listo
                try { allLive(); allVod(); allSeries() } catch (_: Exception) { }
            }
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
        com.visor.tv.VisorApp.instance.epg.clear()
    }

    // ---------------- Listados ----------------

    private fun categories(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }
        .map { Category(it.s("category_id"), it.s("category_name")) }

    suspend fun liveCategories(): List<Category> = memo("lc") { compact("&action=get_live_categories", Category.serializer(), ::categories) }
    suspend fun vodCategories(): List<Category> = memo("vc") { compact("&action=get_vod_categories", Category.serializer(), ::categories) }
    suspend fun seriesCategories(): List<Category> = memo("sc") { compact("&action=get_series_categories", Category.serializer(), ::categories) }

    private fun parseLive(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }.map {
        LiveStream(it.i("stream_id"), it.i("num"), it.s("name"), it.s("stream_icon"), it.s("category_id"), it.s("epg_channel_id"))
    }

    private fun parseVod(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }.map {
        VodStream(it.i("stream_id"), it.s("name"), it.s("stream_icon"), it.s("rating"),
            it.s("container_extension").ifBlank { "mp4" }, it.s("category_id"), it.l("added"), it.year(it.s("name")))
    }

    private fun parseSeries(el: JsonElement) = el.asArr().mapNotNull { it.asObj() }.map {
        SeriesItem(it.i("series_id"), it.s("name"), it.s("cover"), it.s("rating"), it.s("plot"), it.s("category_id"),
            it.l("last_modified"), it.year(it.s("name")))
    }

    // Carpetas: si la lista completa ya está a mano, se filtra al momento; si no, se pide solo esa carpeta.
    suspend fun liveStreams(cat: String): List<LiveStream> = memo("ls:$cat") {
        if (hasFull("ls:*", P_LIVE)) withContext(Dispatchers.Default) { allLive().filter { it.categoryId == cat } }
        else withContext(Dispatchers.Default) { parseLive(cached("&action=get_live_streams&category_id=${enc(cat)}", TTL_LIST)) }
    }

    suspend fun vodStreams(cat: String): List<VodStream> = memo("vs:$cat") {
        if (hasFull("vs:*", P_VOD)) withContext(Dispatchers.Default) { allVod().filter { it.categoryId == cat } }
        else withContext(Dispatchers.Default) { parseVod(cached("&action=get_vod_streams&category_id=${enc(cat)}", TTL_LIST)) }
    }

    suspend fun series(cat: String): List<SeriesItem> = memo("ss:$cat") {
        if (hasFull("ss:*", P_SERIES)) withContext(Dispatchers.Default) { allSeries().filter { it.categoryId == cat } }
        else withContext(Dispatchers.Default) { parseSeries(cached("&action=get_series&category_id=${enc(cat)}", TTL_LIST)) }
    }

    // Listas completas, para el buscador, las novedades y las carpetas. Se guardan en formato compacto.
    suspend fun allLive(): List<LiveStream> = memo("ls:*") { compact(P_LIVE, LiveStream.serializer(), ::parseLive) }
    suspend fun allVod(): List<VodStream> = memo("vs:*") { compact(P_VOD, VodStream.serializer(), ::parseVod) }
    suspend fun allSeries(): List<SeriesItem> = memo("ss:*") { compact(P_SERIES, SeriesItem.serializer(), ::parseSeries) }

    /** Películas por título limpio (sin año, idioma ni calidad), para encontrar en tu lista las que recomienda TMDB. */
    suspend fun vodTitleIndex(): Map<String, List<VodStream>> = memo("vs:idx") {
        withContext(Dispatchers.Default) { allVod().groupBy { normalizeText(cleanTitle(it.name)) } }
    }

    /** Las [n] películas añadidas más recientemente a la lista (sin carpetas ocultas ni bloqueadas). */
    suspend fun latestVod(n: Int = 30): List<VodStream> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val ex = com.visor.tv.VisorApp.instance.folders.excludes("vod")
        val all = allVod().filterNot { ex(it.categoryId) }
        if (all.any { it.added > 0 }) all.sortedByDescending { it.added }.take(n)
        else all.sortedByDescending { it.id }.take(n) // sin fecha: el id suele crecer con cada alta
    }

    /** Las [n] series nuevas o actualizadas más recientemente (sin carpetas ocultas ni bloqueadas). */
    suspend fun latestSeries(n: Int = 30): List<SeriesItem> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val ex = com.visor.tv.VisorApp.instance.folders.excludes("series")
        val all = allSeries().filterNot { ex(it.categoryId) }
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

    private fun videoInfo(v: JsonObject?): String {
        if (v == null) return ""
        val h = v.i("height"); val w = v.i("width")
        val res = when {
            h >= 2000 || w >= 3800 -> "4K"
            h >= 1000 || w >= 1900 -> "1080p"
            h >= 700 || w >= 1270 -> "720p"
            h > 0 -> "${h}p"
            else -> ""
        }
        val codec = v.s("codec_name").uppercase().replace("H264", "H.264").replace("HEVC", "HEVC")
        val hdr = if (v.s("color_transfer").contains("2084") || v.s("color_transfer").contains("hlg")) "HDR" else ""
        return listOf(res, hdr, codec).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun audioInfo(a: JsonObject?): String {
        if (a == null) return ""
        val ch = when (a.i("channels")) { 0 -> ""; 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${a.i("channels")} canales" }
        return listOf(ch, a.s("codec_name").uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
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
            originalName = firstOf(info, "o_name", "original_name"),
            country = firstOf(info, "country"),
            age = firstOf(info, "age", "mpaa_rating", "certification"),
            releaseDate = firstOf(info, "releasedate", "release_date"),
            video = videoInfo(info["video"].asObj()),
            audio = audioInfo(info["audio"].asObj()),
            trailer = firstOf(info, "youtube_trailer", "trailer"),
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
            cast = firstOf(info, "cast", "actors"),
            director = firstOf(info, "director"),
            trailer = firstOf(info, "youtube_trailer", "trailer"),
        )
    }

    // ---------------- EPG ----------------

    /** Dirección de la guía completa (XMLTV). Solo en memoria. */
    fun xmltvUrl(): String {
        val a = acc(); return "${a.base}/xmltv.php?username=${enc(a.user)}&password=${enc(a.pass)}"
    }

    /** Id de EPG de un canal (el que usa la guía completa). */
    suspend fun epgIdOf(streamId: Int): String? =
        memo("ls:epgmap") { allLive().associate { it.id to it.epgId } }[streamId]?.takeIf { it.isNotBlank() }

    /**
     * Guía de un canal: lo que está en emisión y lo siguiente. Primero la guía completa descargada;
     * si ese canal no está en ella, la guía corta de la API (10 minutos en memoria).
     */
    suspend fun shortEpg(streamId: Int, limit: Int = 12): List<EpgEntry> {
        val store = com.visor.tv.VisorApp.instance.epg
        if (store.ready) {
            val id = try { epgIdOf(streamId) } catch (e: Exception) { null }
            if (id != null) store.upcoming(id, limit).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return apiShortEpg(streamId, limit)
    }

    /** Programas de un canal entre dos horas (para la parrilla de la Guía). */
    suspend fun epgRange(streamId: Int, from: Long, to: Long): List<EpgEntry> {
        val store = com.visor.tv.VisorApp.instance.epg
        if (store.ready) {
            val id = try { epgIdOf(streamId) } catch (e: Exception) { null }
            if (id != null) store.range(id, from, to).takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Sin guía completa: la corta solo cubre de ahora en adelante
        return if (to <= System.currentTimeMillis()) emptyList()
        else apiShortEpg(streamId, 24).filter { it.end > from && it.start < to }
    }

    private suspend fun apiShortEpg(streamId: Int, limit: Int): List<EpgEntry> {
        epgMem[streamId]?.let { (t, list) -> if (System.currentTimeMillis() - t < 10 * 60_000) return list }
        val list = try {
            fetch(acc(), "&action=get_short_epg&stream_id=$streamId&limit=$limit").asObj()?.get("epg_listings").asArr()
                .mapNotNull { it.asObj() }
                .map { EpgEntry(b64(it.s("title")), b64(it.s("description")), it.l("start_timestamp") * 1000, it.l("stop_timestamp") * 1000) }
                .filter { it.end > it.start }
                .sortedBy { it.start }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // se dejó de mostrar ese canal: no se guarda nada
        } catch (e: Exception) {
            // Fallo de red: se vuelve a intentar al cabo de un minuto, no de diez
            epgMem[streamId] = (System.currentTimeMillis() - 9 * 60_000L) to emptyList()
            return emptyList()
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
        private const val P_LIVE = "&action=get_live_streams"
        private const val P_VOD = "&action=get_vod_streams"
        private const val P_SERIES = "&action=get_series"
        private val FULL_LISTS = listOf(
            "&action=get_live_categories", "&action=get_vod_categories", "&action=get_series_categories",
            P_LIVE, P_VOD, P_SERIES,
        )
        private const val TTL_LIST = 6 * 60 * 60_000L
        private const val TTL_INFO = 24 * 60 * 60_000L
    }
}
