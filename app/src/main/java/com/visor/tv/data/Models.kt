package com.visor.tv.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class Category(val id: String, val name: String)

data class LiveStream(
    val id: Int,
    val num: Int,
    val name: String,
    val icon: String,
    val categoryId: String,
    val epgId: String,
)

data class VodStream(
    val id: Int,
    val name: String,
    val icon: String,
    val rating: String,
    val ext: String,
    val categoryId: String,
)

data class SeriesItem(
    val id: Int,
    val name: String,
    val cover: String,
    val rating: String,
    val plot: String,
    val categoryId: String,
)

data class VodInfo(
    val name: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val duration: String,
    val year: String,
    val rating: String,
    val cover: String,
    val backdrop: String,
    val tmdbId: String,
    val ext: String,
)

data class Episode(
    val id: Int,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String,
    val duration: String,
)

data class SeriesInfo(
    val name: String,
    val plot: String,
    val cover: String,
    val backdrop: String,
    val genre: String,
    val year: String,
    val rating: String,
    val tmdbId: String,
    val seasons: Map<Int, List<Episode>>,
)

data class EpgEntry(val title: String, val desc: String, val start: Long, val end: Long) {
    fun isNow(now: Long = System.currentTimeMillis()) = now in start until end
}

data class AccountInfo(
    val active: Boolean,
    val status: String,
    val expires: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
)

/** Elemento que se puede reproducir. La URL nunca viaja en él: se construye en el momento de reproducir. */
@Serializable
data class PlayItem(
    val kind: String, // live | vod | episode | file
    val id: Int,
    val ext: String = "",
    val title: String,
    val subtitle: String = "",
    val icon: String = "",
    val epgId: String = "",
    val filePath: String = "",
    val seriesId: Int = 0,
) {
    val key: String get() = "$kind:$id"
}

// ---------- Ayudas para leer el JSON irregular de los paneles Xtream ----------

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

internal fun JsonObject.s(k: String): String {
    val v = this[k] ?: return ""
    return when (v) {
        is JsonPrimitive -> v.contentOrNull ?: ""
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        else -> ""
    }
}

internal fun JsonObject.i(k: String): Int = s(k).toDoubleOrNull()?.toInt() ?: 0
internal fun JsonObject.l(k: String): Long = s(k).toDoubleOrNull()?.toLong() ?: 0L

internal fun b64(s: String): String = try {
    String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
} catch (e: Exception) {
    s
}
