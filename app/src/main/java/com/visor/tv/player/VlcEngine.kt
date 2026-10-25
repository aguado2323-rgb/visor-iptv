package com.visor.tv.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.visor.tv.VisorApp
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Motor VLC: útil con streams o códecs que ExoPlayer no abre bien. */
class VlcEngine(
    context: Context,
    audioLang: String?,
    private val subsLang: String?,
) : PlayerEngine {

    override var onError: ((String) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null

    private val libVlc: LibVLC
    private val mp: MediaPlayer
    private var pendingSeek = 0L
    private var subsApplied = false

    init {
        val opts = arrayListOf("--network-caching=1500", "--http-reconnect", "--no-stats")
        if (audioLang != null) opts += "--audio-language=$audioLang"
        if (subsLang != null) opts += "--sub-language=$subsLang"
        libVlc = LibVLC(context, opts)
        mp = MediaPlayer(libVlc)
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        if (pendingSeek > 0) { mp.setTime(pendingSeek); pendingSeek = 0 }
                        if (!subsApplied) { subsApplied = true; if (subsLang == null) mp.setSpuTrack(-1) }
                    }
                    MediaPlayer.Event.EndReached -> onEnded?.invoke()
                    MediaPlayer.Event.EncounteredError -> onError?.invoke("VLC no pudo abrir el stream")
                }
            }
        })
    }

    override fun prepare(url: String, startMs: Long, isLive: Boolean) {
        val media = Media(libVlc, Uri.parse(url))
        media.addOption(":network-caching=" + if (isLive) "2000" else "1500")
        media.addOption(":http-user-agent=" + VisorApp.USER_AGENT)
        mp.setMedia(media)
        media.release()
        pendingSeek = if (isLive) 0 else startMs
        subsApplied = false
        mp.play()
    }

    override fun togglePause() { if (mp.isPlaying()) mp.pause() else mp.play() }
    override fun isPlaying(): Boolean = mp.isPlaying()
    override fun seekBy(ms: Long) { mp.setTime((mp.getTime() + ms).coerceAtLeast(0)) }
    override fun position(): Long = mp.getTime().coerceAtLeast(0)
    override fun duration(): Long = mp.getLength().coerceAtLeast(0)

    override fun audioTracks(): List<Track> {
        val cur = mp.getAudioTrack()
        return mp.getAudioTracks()?.filter { it.id >= 0 }?.map { Track(it.id.toString(), it.name ?: "Pista ${it.id}", it.id == cur) } ?: emptyList()
    }

    override fun subtitleTracks(): List<Track> {
        val cur = mp.getSpuTrack()
        return mp.getSpuTracks()?.filter { it.id >= 0 }?.map { Track(it.id.toString(), it.name ?: "Subtítulo ${it.id}", it.id == cur) } ?: emptyList()
    }

    override fun selectAudio(id: String) { mp.setAudioTrack(id.toInt()) }
    override fun selectSubtitle(id: String?) { mp.setSpuTrack(id?.toInt() ?: -1) }

    override fun release() {
        try {
            mp.stop()
            mp.detachViews()
        } catch (_: Exception) { }
        mp.release()
        libVlc.release()
    }

    private var aspectMode = com.visor.tv.data.Aspect.FIT
    private var attached = false
    private fun applyAspect() {
        if (!attached) return
        mp.videoScale = when (aspectMode) {
            com.visor.tv.data.Aspect.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            com.visor.tv.data.Aspect.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            com.visor.tv.data.Aspect.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        }
    }
    override fun setAspect(a: com.visor.tv.data.Aspect) { aspectMode = a; applyAspect() }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    layout.isFocusable = false
                    layout.keepScreenOn = true
                    mp.attachViews(layout, null, true, false)
                    attached = true
                    applyAspect()
                }
            },
        )
    }
}
