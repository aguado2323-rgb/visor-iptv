package com.visor.tv.player

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.visor.tv.VisorApp
import com.visor.tv.data.Engine
import com.visor.tv.data.PlayItem
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type

/**
 * Canal en ventana pequeña mientras navegas por la app. Tiene su propio reproductor; al volver a
 * pantalla completa se cierra y el canal se abre de nuevo en el reproductor normal.
 */
object MiniPlayer {
    var item by mutableStateOf<PlayItem?>(null)
        private set
    var engine by mutableStateOf<PlayerEngine?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val active: Boolean get() = item != null

    fun start(app: VisorApp, it: PlayItem, engineType: Engine) {
        stop()
        val audio = app.settings.audioLang.code
        val subs = app.settings.subsDefault.code
        val e = if (engineType == Engine.VLC) VlcEngine(app, audio, subs) else ExoEngine(app, app.http, audio, subs)
        e.onError = { msg -> error = msg }
        e.setAspect(app.settings.aspect)
        try {
            e.prepare(app.repo.urlFor(it, app.settings.liveFormat.ext), 0, true)
        } catch (ex: Exception) {
            e.release()
            return
        }
        error = null
        engine = e
        item = it
    }

    fun stop() {
        engine?.release()
        engine = null
        item = null
        error = null
    }

    /** Vuelve a pantalla completa con el mismo canal (la cola de canales se mantiene). */
    fun expand(ctx: Context) {
        if (item == null) return
        stop()
        VisorApp.instance.internalNav = true
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }
}

/** La ventana en la esquina. No recibe el foco: el mando sigue moviéndose por la app. */
@Composable
fun MiniPlayerWindow(modifier: Modifier = Modifier) {
    val e = MiniPlayer.engine ?: return
    val it = MiniPlayer.item ?: return
    val p = LocalPalette.current
    Column(
        modifier
            .width(336.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .border(1.dp, p.line, RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(189.dp)) {
            key(e) { e.VideoSurface(Modifier.fillMaxSize()) }
            MiniPlayer.error?.let { msg ->
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(12.dp), contentAlignment = Alignment.Center) {
                    T("No se pudo reproducir ($msg)", Type.small, Color.White, maxLines = 3)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(listOf(it.subtitle, it.title).filter { s -> s.isNotBlank() }.joinToString(" · "), Type.small, p.text, Modifier.weight(1f))
            T("  ⏯ pantalla completa", Type.small, p.muted)
        }
    }
}
