package com.visor.tv.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class FavItem(
    val type: String, // live | vod | series
    val id: Int,
    val name: String,
    val icon: String = "",
    val ext: String = "",
    val epgId: String = "",
    val num: Int = 0,
) {
    val key get() = "$type:$id"
}

/** Favoritos de canales, películas y series, guardados en el propio dispositivo. */
class Favorites(context: Context) {
    private val file = File(context.filesDir, "favorites.json")
    private val json = Json { ignoreUnknownKeys = true }
    val items = mutableStateListOf<FavItem>()

    init {
        try {
            if (file.exists()) items.addAll(json.decodeFromString<List<FavItem>>(file.readText()))
        } catch (_: Exception) { }
    }

    fun of(type: String): List<FavItem> = items.filter { it.type == type }
    fun contains(type: String, id: Int) = items.any { it.type == type && it.id == id }

    fun toggle(item: FavItem): Boolean {
        val existing = items.indexOfFirst { it.key == item.key }
        val nowFav = if (existing >= 0) { items.removeAt(existing); false } else { items.add(item); true }
        save()
        return nowFav
    }

    private fun save() {
        try { file.writeText(json.encodeToString(items.toList())) } catch (_: Exception) { }
    }
}

@Serializable
data class ProgressEntry(
    val item: PlayItem,
    val positionMs: Long,
    val durationMs: Long,
    val updated: Long,
)

/** Puntos de reanudación para "Seguir viendo". */
class Progress(context: Context) {
    private val file = File(context.filesDir, "progress.json")
    private val json = Json { ignoreUnknownKeys = true }
    val entries = mutableStateListOf<ProgressEntry>()

    init {
        try {
            if (file.exists()) entries.addAll(json.decodeFromString<List<ProgressEntry>>(file.readText()))
        } catch (_: Exception) { }
    }

    fun positionOf(key: String): Long = entries.firstOrNull { it.item.key == key }?.positionMs ?: 0L

    fun update(item: PlayItem, pos: Long, dur: Long) {
        if (item.kind == "live" || dur <= 0) return
        entries.removeAll { it.item.key == item.key }
        // Si queda menos del 5 %, se da por vista y sale de "Seguir viendo".
        if (pos > 30_000 && pos < dur * 0.95) {
            entries.add(0, ProgressEntry(item, pos, dur, System.currentTimeMillis()))
        }
        while (entries.size > 40) entries.removeAt(entries.lastIndex)
        try { file.writeText(json.encodeToString(entries.toList())) } catch (_: Exception) { }
    }
}
