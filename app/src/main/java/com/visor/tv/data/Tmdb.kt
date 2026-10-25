package com.visor.tv.data

import com.visor.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

data class TmdbArt(val poster: String, val backdrop: String, val overview: String, val rating: String)

/**
 * Carátulas y sinopsis de TMDB, opcionales. La clave va dentro de la app al compilar,
 * así que no hay que configurar nada en el Fire TV. Solo se usa si en Ajustes se elige TMDB
 * y el proveedor indica el identificador TMDB del título.
 */
class Tmdb(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, TmdbArt>()
    private val json = Json { ignoreUnknownKeys = true }

    val available: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    suspend fun art(tmdbId: String, isTv: Boolean): TmdbArt? {
        if (!available || tmdbId.isBlank() || tmdbId == "0") return null
        val key = (if (isTv) "tv:" else "movie:") + tmdbId
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.themoviedb.org/3/${if (isTv) "tv" else "movie"}/$tmdbId?language=es-ES&api_key=${BuildConfig.TMDB_API_KEY}"
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val o = json.parseToJsonElement(r.body?.string() ?: return@withContext null).asObj() ?: return@withContext null
                    val img = "https://image.tmdb.org/t/p/"
                    TmdbArt(
                        poster = o.s("poster_path").takeIf { it.isNotBlank() }?.let { img + "w500" + it } ?: "",
                        backdrop = o.s("backdrop_path").takeIf { it.isNotBlank() }?.let { img + "w1280" + it } ?: "",
                        overview = o.s("overview"),
                        rating = o.s("vote_average").toDoubleOrNull()?.let { "%.1f".format(it) } ?: "",
                    ).also { cache[key] = it }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
