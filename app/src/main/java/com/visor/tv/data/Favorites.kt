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

    /** Para restaurar una copia de seguridad. */
    fun replaceAll(list: List<FavItem>) {
        items.clear(); items.addAll(list); save()
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
    /** Episodio terminado: sirve para proponer el siguiente en "Seguir viendo". */
    val done: Boolean = false,
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

    fun positionOf(key: String): Long = entries.firstOrNull { it.item.key == key && !it.done }?.positionMs ?: 0L

    /** Quita algo de "Seguir viendo". En una serie quita todos sus episodios. */
    fun remove(item: PlayItem) {
        if (item.kind == "episode" && item.seriesId > 0) entries.removeAll { it.item.kind == "episode" && it.item.seriesId == item.seriesId }
        else entries.removeAll { it.item.key == item.key }
        save()
    }

    private fun save() {
        try { file.writeText(json.encodeToString(entries.toList())) } catch (_: Exception) { }
    }

    fun replaceAll(list: List<ProgressEntry>) {
        entries.clear(); entries.addAll(list)
        try { file.writeText(json.encodeToString(entries.toList())) } catch (_: Exception) { }
    }

    fun update(item: PlayItem, pos: Long, dur: Long) {
        if (item.kind == "live" || dur <= 0) return
        entries.removeAll { it.item.key == item.key }
        // Si queda menos del 5 %, se da por vista. Una película sale de "Seguir viendo"; de una serie se
        // recuerda el episodio terminado (solo el último) para proponer el siguiente.
        if (pos > 30_000 && pos < dur * 0.95) {
            entries.add(0, ProgressEntry(item, pos, dur, System.currentTimeMillis()))
        } else if (pos >= dur * 0.95 && item.kind == "episode" && item.seriesId > 0) {
            entries.removeAll { it.done && it.item.seriesId == item.seriesId }
            entries.add(0, ProgressEntry(item, dur, dur, System.currentTimeMillis(), done = true))
        }
        while (entries.size > 60) entries.removeAt(entries.lastIndex)
        save()
    }
}


/** Últimos canales vistos, el más reciente primero. */
class RecentChannels(context: Context) {
    private val file = File(context.filesDir, "recent_channels.json")
    private val json = Json { ignoreUnknownKeys = true }
    val items = mutableStateListOf<PlayItem>()

    init {
        try {
            if (file.exists()) items.addAll(json.decodeFromString<List<PlayItem>>(file.readText()))
        } catch (_: Exception) { }
    }

    fun add(item: PlayItem) {
        if (item.kind != "live") return
        items.removeAll { it.id == item.id }
        items.add(0, item)
        while (items.size > 15) items.removeAt(items.lastIndex)
        try { file.writeText(json.encodeToString(items.toList())) } catch (_: Exception) { }
    }
}
