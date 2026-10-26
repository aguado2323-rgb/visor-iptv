package com.visor.tv.data

import com.visor.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Lo que TMDB sabe de una película o serie, para completar (o sustituir) los datos del proveedor. */
data class TmdbInfo(
    val id: String,
    val poster: String = "",
    val backdrop: String = "",
    /** Logotipo del título (PNG transparente), en español o inglés. */
    val logo: String = "",
    val overview: String = "",
    val rating: String = "",
    val runtimeMin: Int = 0,
    val genres: String = "",
    /** Calificación por edades en España (o EE. UU. si no hay). */
    val certification: String = "",
    val cast: String = "",
    /** Dirección (películas) o creadores (series). */
    val director: String = "",
    val originalTitle: String = "",
    val releaseDate: String = "",
    val country: String = "",
    val recommendations: List<TmdbRec> = emptyList(),
)

data class TmdbRec(val title: String, val originalTitle: String, val year: Int)

data class TmdbEpisode(val num: Int, val name: String, val overview: String, val still: String, val runtimeMin: Int)

/**
 * TMDB, opcional: la clave va dentro de la app al compilar. Completa las fichas con imágenes apaisadas,
 * logotipos, reparto, edad, episodios con imagen y sinopsis, y títulos parecidos. Si el proveedor no da el
 * identificador TMDB, se busca por título y año.
 */
class Tmdb(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, Any>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }

    val available: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    private fun url(path: String, vararg params: Pair<String, String>): String {
        val b = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", BuildConfig.TMDB_API_KEY)
            .addQueryParameter("language", "es-ES")
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private suspend fun get(u: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) null else json.parseToJsonElement(r.body?.string() ?: return@use null).asObj()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { null }
    }

    private fun img(size: String, path: String) = if (path.isBlank()) "" else "https://image.tmdb.org/t/p/$size$path"

    /** Id de TMDB: el del proveedor o, si no hay, el primer resultado de buscar por título (y año). */
    private suspend fun resolve(providerId: String?, name: String, year: Int, isTv: Boolean): String? {
        providerId?.trim()?.takeIf { it.isNotBlank() && it != "0" && it.all(Char::isDigit) }?.let { return it }
        val title = cleanTitle(name)
        if (title.isBlank()) return null
        val y = if (year > 0) year else yearIn(name)
        val key = "s:${if (isTv) "tv" else "mv"}:$title:$y"
        (cache[key] as? String)?.let { return it }
        if (key in misses) return null
        val params = buildList {
            add("query" to title)
            if (y > 0) add((if (isTv) "first_air_date_year" else "year") to y.toString())
        }.toTypedArray()
        var res = get(url(if (isTv) "search/tv" else "search/movie", *params))?.get("results").asArr()
        if (res.isEmpty() && y > 0) res = get(url(if (isTv) "search/tv" else "search/movie", "query" to title))?.get("results").asArr()
        val id = res.firstOrNull()?.asObj()?.s("id")?.takeIf { it.isNotBlank() }
        if (id == null) misses += key else cache[key] = id
        return id
    }

    suspend fun movie(providerId: String?, name: String, year: Int): TmdbInfo? {
        if (!available) return null
        val id = resolve(providerId, name, year, isTv = false) ?: return null
        (cache["mv:$id"] as? TmdbInfo)?.let { return it }
        val o = get(url("movie/$id", "append_to_response" to "credits,release_dates,images,recommendations", "include_image_language" to "es,en,null"))
            ?: return null
        val cert = o["release_dates"].asObj()?.get("results").asArr().mapNotNull { it.asObj() }.let { list ->
            fun of(c: String) = list.firstOrNull { it.s("iso_3166_1") == c }?.get("release_dates").asArr()
                .mapNotNull { it.asObj()?.s("certification") }.firstOrNull { it.isNotBlank() }
            of("ES") ?: of("US")
        }.orEmpty()
        return TmdbInfo(
            id = id,
            poster = img("w500", o.s("poster_path")),
            backdrop = img("w1280", o.s("backdrop_path")),
            logo = logoOf(o),
            overview = o.s("overview"),
            rating = o.s("vote_average").toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "",
            runtimeMin = o.s("runtime").toIntOrNull() ?: 0,
            genres = o["genres"].asArr().mapNotNull { it.asObj()?.s("name") }.take(2).joinToString(" · "),
            certification = cert,
            cast = castOf(o),
            director = o["credits"].asObj()?.get("crew").asArr().mapNotNull { it.asObj() }
                .filter { it.s("job") == "Director" }.map { it.s("name") }.distinct().take(2).joinToString(", "),
            originalTitle = o.s("original_title"),
            releaseDate = prettyDate(o.s("release_date")),
            country = o["production_countries"].asArr().firstOrNull()?.asObj()?.s("name").orEmpty(),
            recommendations = recsOf(o, isTv = false),
        ).also { cache["mv:$id"] = it }
    }

    suspend fun tv(providerId: String?, name: String, year: Int): TmdbInfo? {
        if (!available) return null
        val id = resolve(providerId, name, year, isTv = true) ?: return null
        (cache["tv:$id"] as? TmdbInfo)?.let { return it }
        val o = get(url("tv/$id", "append_to_response" to "credits,content_ratings,images,recommendations", "include_image_language" to "es,en,null"))
            ?: return null
        val cert = o["content_ratings"].asObj()?.get("results").asArr().mapNotNull { it.asObj() }.let { list ->
            (list.firstOrNull { it.s("iso_3166_1") == "ES" } ?: list.firstOrNull { it.s("iso_3166_1") == "US" })?.s("rating")
        }.orEmpty()
        return TmdbInfo(
            id = id,
            poster = img("w500", o.s("poster_path")),
            backdrop = img("w1280", o.s("backdrop_path")),
            logo = logoOf(o),
            overview = o.s("overview"),
            rating = o.s("vote_average").toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "",
            runtimeMin = o["episode_run_time"].asArr().firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0,
            genres = o["genres"].asArr().mapNotNull { it.asObj()?.s("name") }.take(2).joinToString(" · "),
            certification = cert,
            cast = castOf(o),
            director = o["created_by"].asArr().mapNotNull { it.asObj()?.s("name") }.take(2).joinToString(", "),
            originalTitle = o.s("original_name"),
            releaseDate = prettyDate(o.s("first_air_date")),
            country = o["origin_country"].asArr().firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
            recommendations = recsOf(o, isTv = true),
        ).also { cache["tv:$id"] = it }
    }

    /** Episodios de una temporada (imagen, título y sinopsis en español), por número de episodio. */
    @Suppress("UNCHECKED_CAST")
    suspend fun season(tvId: String, season: Int): Map<Int, TmdbEpisode> {
        if (!available || tvId.isBlank()) return emptyMap()
        val key = "season:$tvId:$season"
        (cache[key] as? Map<Int, TmdbEpisode>)?.let { return it }
        val o = get(url("tv/$tvId/season/$season")) ?: return emptyMap()
        val map = o["episodes"].asArr().mapNotNull { it.asObj() }.associate { e ->
            val n = e.s("episode_number").toIntOrNull() ?: 0
            n to TmdbEpisode(n, e.s("name"), e.s("overview"), img("w300", e.s("still_path")), e.s("runtime").toIntOrNull() ?: 0)
        }
        cache[key] = map
        return map
    }

    private fun logoOf(o: JsonObject): String {
        val logos = o["images"].asObj()?.get("logos").asArr().mapNotNull { it.asObj() }
        val pick = logos.firstOrNull { it.s("iso_639_1") == "es" } ?: logos.firstOrNull { it.s("iso_639_1") == "en" } ?: logos.firstOrNull()
        return pick?.s("file_path")?.let { img("w500", it) }.orEmpty()
    }

    private fun castOf(o: JsonObject) = o["credits"].asObj()?.get("cast").asArr().mapNotNull { it.asObj()?.s("name") }
        .filter { it.isNotBlank() }.take(5).joinToString(", ")

    private fun recsOf(o: JsonObject, isTv: Boolean) = o["recommendations"].asObj()?.get("results").asArr().mapNotNull { it.asObj() }.map {
        TmdbRec(
            title = it.s(if (isTv) "name" else "title"),
            originalTitle = it.s(if (isTv) "original_name" else "original_title"),
            year = it.s(if (isTv) "first_air_date" else "release_date").take(4).toIntOrNull() ?: 0,
        )
    }

    private fun prettyDate(iso: String): String = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)
        if (d == null) "" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("es")).format(d)
    } catch (e: Exception) { "" }
}

private val YEAR = Regex("""(?:^|[\s(\[\-|])((?:19|20)\d{2})(?:[\s)\]\-|]|$)""")

/** Año dentro del nombre ("Título (2023)"), o 0. */
fun yearIn(name: String): Int = YEAR.findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0

/**
 * Título sin los añadidos típicos de las listas IPTV: prefijos de idioma o país ("ES - ", "|ES| ", "[ES]"),
 * calidad (4K, FHD, HD…), versión (VOSE, MULTI…) y el año.
 */
fun cleanTitle(name: String): String {
    var t = name
    t = t.replace(CT_PREFIX, "")
    t = t.replace(CT_YEAR_PAREN, " ")
    t = t.replace(CT_YEAR_TAIL, " ")
    t = t.replace(CT_QUALITY, " ")
    t = t.replace(CT_BRACKETS, " ")
    return t.replace(CT_SPACES, " ").trim().trimEnd('-', ':').trim()
}

// Compiladas una sola vez (cleanTitle se usa con toda la lista de películas)
private val CT_PREFIX = Regex("""^\s*(\[[^\]]{1,12}\]|\|[^|]{1,12}\||[A-Z]{2,4}\s*[-:|]\s+)+""")
private val CT_YEAR_PAREN = Regex("""[\[(]\s*(19|20)\d{2}\s*[\])]""")
private val CT_YEAR_TAIL = Regex("""\s[-|]\s*(19|20)\d{2}\s*$""")
private val CT_QUALITY = Regex("""(?i)\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hdr|vose|vos|multi|dual|latino|castellano|subs?)\b""")
private val CT_BRACKETS = Regex("""[\[\]()|]""")
private val CT_SPACES = Regex("""\s+""")
