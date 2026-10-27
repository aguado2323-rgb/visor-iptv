package com.visor.tv.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import kotlinx.coroutines.delay
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Opciones de VLC pensadas para cámaras en directo: poco retardo y RTSP por TCP (más estable por wifi). */
private val CAMERA_OPTS = listOf(
    "--rtsp-tcp", "--network-caching=500", "--no-stats", "--drop-late-frames", "--skip-frames",
)

/**
 * Vídeo de una cámara en directo. Si la conexión falla o se corta, vuelve a intentarlo sola cada pocos
 * segundos. [muted] quita el sonido (en el mosaico solo se oye la cámara abierta a pantalla completa).
 * [startDelayMs] evita abrir conexiones al pasar de largo por la pestaña con el mando.
 */
@Composable
fun CameraVideo(
    url: String,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    textureView: Boolean = true,
    startDelayMs: Long = 0,
) {
    // Una dirección nueva empieza con un reproductor nuevo
    androidx.compose.runtime.key(url, muted, textureView) {
        CameraVideoInner(url, modifier, muted, textureView, startDelayMs)
    }
}

@Composable
private fun CameraVideoInner(url: String, modifier: Modifier, muted: Boolean, textureView: Boolean, startDelayMs: Long) {
    val ctx = LocalContext.current
    val libVlc = remember { VlcShared.get(ctx, CAMERA_OPTS) }
    val mp = remember { MediaPlayer(libVlc) }
    var status by remember { mutableStateOf("Conectando…") }
    // Cada vez que cambia, se vuelve a abrir el vídeo (reintento tras un fallo o un corte)
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(mp) {
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) status = ""
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> failed = true
            }
        }
        onDispose {
            try {
                mp.setEventListener(null)
                mp.stop()
                mp.detachViews()
            } catch (_: Exception) { }
            mp.release()
        }
    }

    LaunchedEffect(mp, attempt) {
        if (attempt == 0 && startDelayMs > 0) delay(startDelayMs)
        failed = false
        val media = Media(libVlc, Uri.parse(url))
        media.addOption(":network-caching=500")
        if (muted) media.addOption(":no-audio")
        mp.setMedia(media)
        media.release()
        mp.play()
    }

    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        status = "Sin conexión, reintentando…"
        delay(5_000)
        attempt++
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                VLCVideoLayout(c).also { layout ->
                    layout.isFocusable = false
                    layout.keepScreenOn = true
                    mp.attachViews(layout, null, false, textureView)
                    mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
            },
        )
        if (status.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                T(status, Type.small, Color.White.copy(alpha = 0.8f))
            }
        }
    }
}
