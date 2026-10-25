package com.visor.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.visor.tv.data.PlayItem

data class Track(val id: String, val label: String, val selected: Boolean)

/** Capa común para ExoPlayer y VLC: la interfaz del reproductor no sabe cuál está debajo. */
interface PlayerEngine {
    var onError: ((String) -> Unit)?
    var onEnded: (() -> Unit)?

    fun prepare(url: String, startMs: Long, isLive: Boolean)
    fun togglePause()
    fun isPlaying(): Boolean
    fun seekBy(ms: Long)
    fun position(): Long
    fun duration(): Long
    fun audioTracks(): List<Track>
    fun subtitleTracks(): List<Track>
    fun selectAudio(id: String)
    /** null desactiva los subtítulos. */
    fun selectSubtitle(id: String?)
    fun release()
    /** Formato de imagen (ajustar, rellenar, zoom). */
    fun setAspect(a: com.visor.tv.data.Aspect)

    @Composable
    fun VideoSurface(modifier: Modifier)
}

/** Cola de reproducción compartida (canales de la carpeta o episodios de la temporada). */
class PlaybackQueue {
    var items: List<PlayItem> = emptyList()
        private set
    var index: Int = 0

    fun set(list: List<PlayItem>, start: Int) {
        items = list
        index = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    val current: PlayItem? get() = items.getOrNull(index)

    fun move(delta: Int): PlayItem? {
        if (items.isEmpty()) return null
        index = (index + delta + items.size) % items.size
        return current
    }
}

internal fun langLabel(code: String?, fallback: String): String {
    if (code.isNullOrBlank() || code == "und") return fallback
    return try {
        java.util.Locale(code).getDisplayLanguage(java.util.Locale("es")).replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        code
    }
}
