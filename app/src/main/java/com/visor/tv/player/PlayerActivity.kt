package com.visor.tv.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.Img
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.visor.tv.VisorApp
import com.visor.tv.data.EpgEntry
import com.visor.tv.data.Engine
import com.visor.tv.data.FavItem
import com.visor.tv.data.PlayItem
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.Pill
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import com.visor.tv.ui.VisorTheme
import com.visor.tv.ui.screens.hm
import com.visor.tv.ui.screens.startManualRecording
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as VisorApp
        if (app.queue.current == null) { finish(); return }
        setContent {
            VisorTheme(app.settings.theme) {
                PlayerScreen(app) { finish() }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PlayerScreen(app: VisorApp, onExit: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var item by remember { mutableStateOf(app.queue.current!!) }
    var engineType by remember { mutableStateOf(app.settings.engine) }
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var epgNow by remember { mutableStateOf<EpgEntry?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tracksVersion by remember { mutableStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val isLive = item.kind == "live"

    val engine: PlayerEngine = remember(engineType) {
        val audio = app.settings.audioLang.code
        val subs = app.settings.subsDefault.code
        if (engineType == Engine.VLC) VlcEngine(ctx, audio, subs) else ExoEngine(ctx, app.http, audio, subs)
    }

    fun saveProgress() {
        if (!isLive) app.progress.update(item, engine.position(), engine.duration())
    }

    fun start(it: PlayItem) {
        error = null
        val url = try { app.repo.urlFor(it, app.settings.liveFormat.ext) } catch (e: Exception) { error = "Sin cuenta"; return }
        engine.prepare(url, app.progress.positionOf(it.key), it.kind == "live")
        playing = true
    }

    DisposableEffect(engine) {
        engine.onError = { error = it; overlay = true }
        engine.onEnded = {
            saveProgress()
            if (item.kind == "episode" && app.queue.index < app.queue.items.lastIndex) {
                app.queue.move(1)?.let { n -> item = n; start(n) }
            } else if (!isLive) onExit()
        }
        start(item)
        onDispose {
            saveProgress()
            engine.release()
        }
    }

    // EPG del canal en directo
    LaunchedEffect(item.key) {
        epgNow = null
        if (item.kind == "live") epgNow = app.repo.shortEpg(item.id, 3).firstOrNull { it.isNow() }
    }
    // Posición, auto-ocultar y guardado periódico
    LaunchedEffect(engine) {
        var tick = 0
        while (true) {
            delay(500)
            pos = engine.position(); dur = engine.duration(); playing = engine.isPlaying()
            if (overlay && !panel && error == null && System.currentTimeMillis() - lastKey > 6000) overlay = false
            if (guide && System.currentTimeMillis() - lastKey > 15000) guide = false
            if (++tick % 60 == 0) saveProgress()
        }
    }
    LaunchedEffect(overlay, guide) {
        if (guide) return@LaunchedEffect // la guía coloca el foco en el canal actual
        if (!overlay) try { rootFocus.requestFocus() } catch (_: Exception) { }
        else try {
            // En películas y series el foco va a la barra de tiempo para poder avanzar enseguida
            if (!isLive && engine.duration() > 0) seekFocus.requestFocus() else firstButton.requestFocus()
        } catch (_: Exception) { }
    }

    fun jumpTo(i: Int) {
        saveProgress()
        app.queue.index = i
        app.queue.current?.let { n -> item = n; start(n) }
        guide = false
        overlay = true
        lastKey = System.currentTimeMillis()
    }

    fun zap(delta: Int) {
        saveProgress()
        app.queue.move(delta)?.let { n -> item = n; start(n); overlay = true; lastKey = System.currentTimeMillis() }
    }

    BackHandler {
        when {
            guide -> guide = false
            panel -> panel = false
            overlay && error == null -> overlay = false
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) lastKey = System.currentTimeMillis()
                if (e.type != KeyEventType.KeyDown || overlay || guide) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { if (isLive) zap(-1) else overlay = true; true }
                    Key.DirectionDown -> { if (isLive) zap(1) else overlay = true; true }
                    Key.DirectionLeft -> { if (isLive) guide = true else { engine.seekBy(-10_000); overlay = true }; true }
                    Key.DirectionRight -> { if (!isLive) engine.seekBy(30_000); overlay = true; true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { engine.togglePause(); true }
                    Key.MediaFastForward -> { engine.seekBy(30_000); true }
                    Key.MediaRewind -> { engine.seekBy(-10_000); true }
                    Key.Menu -> { overlay = true; if (!isLive) panel = true; true }
                    // En directo, OK abre la guía de canales sin salir del vídeo; ≡ Menú muestra los controles.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (isLive) guide = true else overlay = true; true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        key(engine) { engine.VideoSurface(Modifier.fillMaxSize()) }

        val rec = app.recordings.activeFor(item.id).takeIf { isLive }
        if (rec != null) {
            Row(Modifier.padding(28.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("● REC  hasta ${hm(rec.end)}", p.rec, Color.White)
            }
        }

        // Con el panel de audio y subtítulos abierto, los controles de abajo se ocultan para no quedar cortados.
        if (overlay && !panel) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, end = 28.dp, bottom = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.surface.copy(alpha = 0.94f))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                T(listOf(item.subtitle, item.title).filter { it.isNotBlank() }.joinToString(" · "), Type.h2)
                if (isLive) {
                    val e = epgNow
                    T(if (e != null) "${hm(e.start)} – ${hm(e.end)} · ${e.title}" else "En directo", Type.small, p.muted)
                    T("OK o ← guía de canales · ↑ ↓ cambiar de canal · ≡ opciones", Type.small, p.muted)
                    if (e != null) {
                        val frac = ((System.currentTimeMillis() - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f)
                        ProgressBar(frac)
                    }
                } else if (dur > 0) {
                    SeekBar(pos, dur, seekFocus) { target ->
                        engine.seekBy(target - engine.position())
                        pos = target
                    }
                }
                if (error != null) {
                    T("No se pudo reproducir ($error). Prueba con el otro reproductor o con otro formato en Ajustes.", Type.small, p.rec, maxLines = 2)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(if (playing) "Pausa" else "Reanudar", focusRequester = firstButton, onClick = { engine.togglePause() })
                    if (!isLive) {
                        TvButton("−10 s", onClick = { engine.seekBy(-10_000) })
                        TvButton("+30 s", onClick = { engine.seekBy(30_000) })
                    }
                    TvButton("Audio y subtítulos", onClick = { panel = !panel; tracksVersion++ })
                    if (isLive) {
                        if (rec == null) TvButton("Grabar", dot = p.rec, onClick = { startManualRecording(app, item.id, item.title, epgNow) })
                        else TvButton("Detener grabación", dot = p.rec, onClick = { app.recordings.cancel(rec.id) })
                    }
                    val favType = when (item.kind) { "live" -> "live"; "vod" -> "vod"; else -> null }
                    if (favType != null) {
                        val fav = app.favorites.contains(favType, item.id)
                        TvButton(if (fav) "★" else "☆", selected = fav, onClick = {
                            app.favorites.toggle(FavItem(type = favType, id = item.id, name = item.title, icon = item.icon, ext = item.ext, epgId = item.epgId, num = item.subtitle.toIntOrNull() ?: 0))
                        })
                    }
                    TvButton("Usar ${if (engineType == Engine.EXO) "VLC" else "ExoPlayer"}", onClick = {
                        saveProgress()
                        engineType = if (engineType == Engine.EXO) Engine.VLC else Engine.EXO
                    })
                }
            }
        }

        if (panel) {
            TracksPanel(engine, tracksVersion, Modifier.align(Alignment.CenterEnd)) { tracksVersion++ }
        }

        if (guide && isLive) {
            ChannelGuidePanel(app, app.queue.items, app.queue.index, Modifier.align(Alignment.CenterStart)) { i -> jumpTo(i) }
        }
    }
}

@Composable
private fun ProgressBar(frac: Float) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.line)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).background(p.text))
    }
}

@Composable
private fun TracksPanel(engine: PlayerEngine, version: Int, modifier: Modifier, onChanged: () -> Unit) {
    val p = LocalPalette.current
    val audio = remember(version) { engine.audioTracks() }
    val subs = remember(version) { engine.subtitleTracks() }
    val first = remember { FocusRequester() }
    Column(
        modifier
            .width(460.dp)
            .fillMaxHeight()
            .background(p.surface.copy(alpha = 0.97f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        T("Audio y subtítulos", Type.heading)
        T("Atrás para cerrar", Type.small, p.muted)
        T("IDIOMA DEL AUDIO", Type.small.copy(fontWeight = FontWeight.Bold), p.muted, Modifier.padding(top = 18.dp, bottom = 4.dp))
        if (audio.isEmpty()) T("Este contenido solo tiene una pista de audio", Type.body, p.muted)
        audio.forEachIndexed { i, t ->
            TrackRow(t.label, t.selected, if (i == 0) first else null) { engine.selectAudio(t.id); onChanged() }
        }
        T("SUBTÍTULOS", Type.small.copy(fontWeight = FontWeight.Bold), p.muted, Modifier.padding(top = 18.dp, bottom = 4.dp))
        TrackRow("Desactivados", subs.none { it.selected }, if (audio.isEmpty()) first else null) { engine.selectSubtitle(null); onChanged() }
        subs.forEach { t ->
            TrackRow(t.label, t.selected, null) { engine.selectSubtitle(t.id); onChanged() }
        }
        if (subs.isEmpty()) T("Este contenido no trae subtítulos", Type.body, p.muted, Modifier.padding(top = 4.dp))
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}


/** Guía de canales encima del vídeo: la lista de la carpeta con lo que echan ahora en cada canal. */
@Composable
private fun ChannelGuidePanel(app: VisorApp, items: List<PlayItem>, current: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(p.surface.copy(alpha = 0.96f))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T("Canales", Type.h2, modifier = Modifier.padding(start = 8.dp))
        T("OK para cambiar · Atrás para cerrar", Type.small, p.muted, Modifier.padding(start = 8.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
                GuideChannelRow(app, it, i == current, if (i == current) currentFocus else null) { onPick(i) }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        try { currentFocus.requestFocus() } catch (_: Exception) { }
    }
}

@Composable
private fun GuideChannelRow(app: VisorApp, it: PlayItem, playing: Boolean, fr: FocusRequester?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val epg by produceState<List<EpgEntry>?>(null, it.id) {
        delay(200) // al bajar rápido no se piden guías que no se llegan a ver
        value = app.repo.shortEpg(it.id, 3)
    }
    val now = epg?.firstOrNull { e -> e.isNow() }
    val next = epg?.firstOrNull { e -> now != null && e.start >= now.end }
    Focusable(onClick = onClick, focusRequester = fr, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { focused ->
        val fg = if (focused) p.focusText else p.text
        Row(
            Modifier.fillMaxWidth().background(if (focused) p.focusBg else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Img(it.icon, Modifier.width(44.dp).height(30.dp).clip(RoundedCornerShape(6.dp)), crop = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (playing) Box(Modifier.size(7.dp).clip(CircleShape).background(p.rec))
                    T((if (it.subtitle.isNotBlank()) it.subtitle + "  " else "") + it.title, Type.label, fg)
                }
                T(now?.let { e -> "${e.title} · hasta ${hm(e.end)}" } ?: if (epg == null) "…" else "Sin guía", Type.small, fg.copy(alpha = 0.75f))
                if (now != null) {
                    val frac = ((System.currentTimeMillis() - now.start).toFloat() / (now.end - now.start)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(fg.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(frac).height(2.dp).background(fg))
                    }
                }
                if (focused && next != null) T("Después: ${hm(next.start)} ${next.title}", Type.small, fg.copy(alpha = 0.65f))
            }
        }
    }
}


/** Fila de pista a todo el ancho del panel: marca ✓ la activa y deja hasta dos líneas para nombres largos. */
@Composable
private fun TrackRow(label: String, selected: Boolean, fr: FocusRequester?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Focusable(onClick = onClick, focusRequester = fr, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { focused ->
        val fg = if (focused) p.focusText else p.text
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) p.focusBg else if (selected) p.surface2 else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T(if (selected) "✓" else "", Type.label, fg, Modifier.width(16.dp))
            T(label, Type.label.copy(fontSize = 15.sp), fg, Modifier.weight(1f), maxLines = 2)
        }
    }
}


/**
 * Barra de tiempo navegable con el mando. Con el foco encima, ← → mueven una marca de destino sin cortar
 * el vídeo; si mantienes pulsado, cada vez avanza más (10 s → 30 s → 1 min → 5 min). Al soltar, en menos
 * de un segundo salta ahí; OK salta al momento.
 */
@Composable
private fun SeekBar(pos: Long, dur: Long, fr: FocusRequester, onSeekTo: (Long) -> Unit) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Long?>(null) }
    var stamp by remember { mutableLongStateOf(0L) }

    // Confirma el salto cuando dejas de pulsar
    LaunchedEffect(stamp) {
        if (preview == null) return@LaunchedEffect
        delay(900)
        preview?.let(onSeekTo)
        preview = null
    }

    val shown = preview ?: pos
    val frac = (shown.toFloat() / dur).coerceIn(0f, 1f)
    val playedFrac = (pos.toFloat() / dur).coerceIn(0f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(fmt(shown), Type.label, if (preview != null) p.text else p.muted)
            if (preview != null) {
                val diff = preview!! - pos
                T((if (diff >= 0) "+" else "−") + fmt(kotlin.math.abs(diff)), Type.small, p.muted)
            }
            Box(Modifier.weight(1f))
            T(if (focused) "← → para avanzar o retroceder · mantén pulsado para ir más rápido" else "", Type.small, p.muted)
            T("−" + fmt((dur - shown).coerceAtLeast(0)), Type.small, p.muted)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .focusRequester(fr)
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused && preview != null) { onSeekTo(preview!!); preview = null } }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val rc = e.nativeKeyEvent.repeatCount
                    val step = when {
                        rc < 3 -> 10_000L
                        rc < 10 -> 30_000L
                        rc < 20 -> 60_000L
                        else -> 300_000L
                    }
                    when (e.key) {
                        Key.DirectionRight -> { preview = ((preview ?: pos) + step).coerceAtMost(dur - 1000); stamp = System.nanoTime(); true }
                        Key.DirectionLeft -> { preview = ((preview ?: pos) - step).coerceAtLeast(0); stamp = System.nanoTime(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            preview?.let(onSeekTo); preview = null; true
                        }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = if (focused) 8.dp else 4.dp
            Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(p.line)) {
                // Lo que llevas visto
                Box(Modifier.fillMaxWidth(playedFrac).height(h).background(p.text.copy(alpha = if (preview != null) 0.45f else 1f)))
                // Destino mientras eliges
                if (preview != null) Box(Modifier.fillMaxWidth(frac).height(h).background(p.text))
            }
            if (focused) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .offset(x = (maxWidth - 18.dp) * frac)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(p.text)
                    )
                }
            }
        }
    }
}
