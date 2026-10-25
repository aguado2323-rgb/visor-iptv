package com.visor.tv.player

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class ExoEngine(
    context: Context,
    http: OkHttpClient,
    audioLang: String?,
    subsLang: String?,
) : PlayerEngine {

    override var onError: ((String) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
                .build()
        )
        .build()

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLang)
            .apply {
                if (subsLang == null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else setPreferredTextLanguage(subsLang)
            }
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else {
                    onError?.invoke(error.errorCodeName)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
    }

    override fun prepare(url: String, startMs: Long, isLive: Boolean) {
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        if (startMs > 0 && !isLive) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    override fun togglePause() { player.playWhenReady = !player.playWhenReady }
    override fun isPlaying(): Boolean = player.playWhenReady
    override fun seekBy(ms: Long) { player.seekTo((player.currentPosition + ms).coerceAtLeast(0)) }
    override fun position(): Long = player.currentPosition
    override fun duration(): Long = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    private fun tracksOf(type: Int): List<Track> {
        val out = mutableListOf<Track>()
        player.currentTracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val f = group.getTrackFormat(t)
                val base = f.label ?: langLabel(f.language, "Pista ${out.size + 1}")
                val extra = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
                    when (f.channelCount) { 1 -> " · mono"; 2 -> " · estéreo"; 6 -> " · 5.1"; 8 -> " · 7.1"; else -> " · ${f.channelCount} canales" }
                } else ""
                out += Track("$g:$t", base + extra, group.isTrackSelected(t))
            }
        }
        return out
    }

    override fun audioTracks() = tracksOf(C.TRACK_TYPE_AUDIO)

    override fun subtitleTracks(): List<Track> {
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        return tracksOf(C.TRACK_TYPE_TEXT).map { if (disabled) it.copy(selected = false) else it }
    }

    private fun select(id: String, type: Int) {
        val (g, t) = id.split(":").map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(g) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            .build()
    }

    override fun selectAudio(id: String) = select(id, C.TRACK_TYPE_AUDIO)

    override fun selectSubtitle(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else select(id, C.TRACK_TYPE_TEXT)
    }

    override fun release() = player.release()

    private var aspectMode by androidx.compose.runtime.mutableStateOf(com.visor.tv.data.Aspect.FIT)
    override fun setAspect(a: com.visor.tv.data.Aspect) { aspectMode = a }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        val mode = when (aspectMode) {
            com.visor.tv.data.Aspect.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            com.visor.tv.data.Aspect.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            com.visor.tv.data.Aspect.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        AndroidView(
            modifier = modifier,
            update = { it.resizeMode = mode },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    isFocusable = false
                    player = this@ExoEngine.player
                }
            },
        )
    }
}
