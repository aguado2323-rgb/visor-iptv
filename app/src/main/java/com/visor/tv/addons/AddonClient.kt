package com.visor.tv.addons

import com.visor.tv.data.asArr
import com.visor.tv.data.asObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Dirección de un addon ya separada: base sin "/manifest.json" y lo que llevara detrás. */
data class AddonAddress(val base: String, val query: String)

/**
 * Cliente del protocolo de addons de Stremio. Solo hace peticiones GET de JSON.
 *
 * Las direcciones de los addons pueden llevar dentro la clave del debrid, así que aquí nunca se registran
 * ni se meten en los mensajes de error: los errores solo dicen qué pasó.
 */
class AddonClient(http: OkHttpClient) {
    // Los addons de fuentes consultan a su vez a otros servicios y pueden tardar: más margen que el resto de la app
    private val http = http.newBuilder().readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    private suspend fun get(url: String): JsonObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("el addon respondió con el error ${r.code}")
                val body = r.body?.string().orEmpty()
                try {
                    json.parseToJsonElement(body).asObj()
                } catch (e: Exception) {
                    null
                } ?: throw IOException("el addon no devolvió datos válidos")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // OkHttp incluye la dirección en algunos mensajes (y la dirección puede llevar claves): se sustituye
            throw IOException(safeMessage(e))
        } catch (e: IllegalArgumentException) {
            throw IOException("la dirección del addon no es válida")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, block: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, v) -> if (now - at < ttlMs) return v as T }
        return block().also { cache[key] = now to it }
    }

    fun clearCache() = cache.clear()

    private fun url(a: InstalledAddon, vararg segments: String, extra: List<Pair<String, String>> = emptyList()): String {
        val path = segments.joinToString("/") { encodeSegment(it) }
        val ext = if (extra.isEmpty()) "" else "/" + extra.joinToString("&") { (k, v) -> "$k=${encodeSegment(v)}" }
        return a.base + "/" + path + ext + ".json" + a.query
    }

    /** Descarga el manifiesto de una dirección tal como la escribe o pega el usuario. */
    suspend fun install(input: String): InstalledAddon {
        val addr = parseAddonAddress(input) ?: throw IOException("la dirección no es válida. Debe ser el enlace del manifiesto del addon (termina en /manifest.json)")
        val m = parseManifest(get(addr.base + "/manifest.json" + addr.query))
        if (m.id.isBlank() || m.resources.isEmpty()) throw IOException("eso no parece el manifiesto de un addon")
        return InstalledAddon(base = addr.base, manifest = m, query = addr.query)
    }

    /** Una página (100 títulos) de un catálogo, o el resultado de buscar en él. */
    suspend fun catalog(a: InstalledAddon, c: AddonCatalog, search: String? = null, skip: Int = 0): List<MetaPreview> {
        val extra = buildList {
            if (!search.isNullOrBlank()) add("search" to search.trim())
            if (skip > 0) add("skip" to skip.toString())
        }
        val u = url(a, "catalog", c.type, c.id, extra = extra)
        return cached(u, if (search != null) TTL_SEARCH else TTL_CATALOG) {
            get(u)["metas"].asArr().mapNotNull { it.asObj()?.let { o -> parsePreview(o, c.type) } }.distinctBy { it.id }
        }
    }

    suspend fun meta(a: InstalledAddon, type: String, id: String): MetaDetail? {
        val u = url(a, "meta", type, id)
        return cached(u, TTL_META) { Holder(get(u)["meta"].asObj()?.let { parseMeta(it, type) }) }.value
    }

    suspend fun streams(a: InstalledAddon, type: String, videoId: String): List<AddonStream> {
        val u = url(a, "stream", type, videoId)
        return cached(u, TTL_STREAMS) {
            get(u)["streams"].asArr().mapNotNull { it.asObj()?.let { o -> parseStream(o, a.manifest.name) } }
        }
    }

    suspend fun subtitles(a: InstalledAddon, type: String, videoId: String): List<AddonSubtitle> {
        val u = url(a, "subtitles", type, videoId)
        return cached(u, TTL_META) { get(u)["subtitles"].asArr().mapNotNull { it.asObj()?.let(::parseSubtitle) } }
    }

    private class Holder<T>(val value: T)

    companion object {
        private const val TTL_CATALOG = 30 * 60_000L
        private const val TTL_SEARCH = 5 * 60_000L
        private const val TTL_META = 12 * 60 * 60_000L
        // Las fuentes con debrid caducan: se vuelven a pedir pronto
        private const val TTL_STREAMS = 10 * 60_000L

        private fun safeMessage(e: IOException): String = when (e) {
            is java.net.UnknownHostException -> "no se encuentra el servidor del addon"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "el addon tardó demasiado en responder"
            is javax.net.ssl.SSLException -> "la conexión segura con el addon falló"
            is java.net.ConnectException -> "no se pudo conectar con el addon"
            else -> e.message?.takeIf { "://" !in it } ?: "error de conexión con el addon"
        }
    }
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~:"

/** Codifica un tramo de la ruta. Los dos puntos se dejan tal cual: los identificadores son "tt123:1:2". */
internal fun encodeSegment(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in UNRESERVED) sb.append(c) else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
    }
    return sb.toString()
}

/**
 * Normaliza lo que el usuario escribe o pega: admite "stremio://…", que falte "https://" y que falte
 * "/manifest.json". Devuelve null si no es una dirección web.
 */
fun parseAddonAddress(input: String): AddonAddress? {
    var u = input.trim()
    if (u.isBlank() || u.any { it.isWhitespace() }) return null
    u = when {
        u.startsWith("stremio://", true) -> "https://" + u.substring(10)
        u.startsWith("http://", true) || u.startsWith("https://", true) -> u
        "://" in u -> return null
        else -> "https://$u"
    }
    val q = u.indexOf('?')
    val query = if (q >= 0) u.substring(q) else ""
    var path = (if (q >= 0) u.substring(0, q) else u).trimEnd('/')
    if (path.endsWith("/manifest.json", true)) path = path.dropLast("/manifest.json".length)
    // "/configure" es la página de configuración del addon, no su dirección
    if (path.endsWith("/configure", true)) path = path.dropLast("/configure".length)
    val host = path.substringAfter("://").substringBefore('/')
    if (host.isBlank() || '.' !in host && !host.startsWith("localhost")) return null
    return AddonAddress(path, query)
}
