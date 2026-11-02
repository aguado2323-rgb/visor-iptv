package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visor.tv.VisorApp
import com.visor.tv.data.Category
import com.visor.tv.data.EpgEntry
import com.visor.tv.data.LiveStream
import com.visor.tv.play
import com.visor.tv.record.Recording
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.visor.tv.ui.Img
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.TvButton
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val WINDOW_MIN = 180
private const val STEP_MS = 2 * 3_600_000L
private const val DAY_MS = 86_400_000L

private fun halfHourFloor(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    c.set(Calendar.MINUTE, if (c.get(Calendar.MINUTE) >= 30) 30 else 0)
    return c.timeInMillis
}

private fun dayLabel(t: Long): String {
    fun day0(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when (Math.round((day0(t) - day0(System.currentTimeMillis())) / DAY_MS.toDouble()).toInt()) {
        0 -> "Hoy"
        1 -> "Mañana"
        -1 -> "Ayer"
        else -> SimpleDateFormat("EEEE d", Locale("es")).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

/** Carpeta "Todos los canales" de la guía. */
private const val ALL_FOLDER = "__all"

/** Reproductor de la ventana de la guía; se puede liberar a mano antes de ir a pantalla completa. */
private class PreviewEngine(val e: com.visor.tv.player.PlayerEngine) {
    private var released = false
    fun release() { if (!released) { released = true; e.release() } }
}

@Composable
fun GuideScreen(app: VisorApp) = androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides com.visor.tv.ui.OledPalette) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cats = load(app.repo.version) { app.repo.liveCategories() }
    var selected by rememberSaveable { mutableStateOf(FAV_FOLDER) }
    var info by remember { mutableStateOf<Pair<LiveStream, EpgEntry?>?>(null) }
    var dialog by remember { mutableStateOf<Pair<LiveStream, EpgEntry>?>(null) }
    var menuFor by remember { mutableStateOf<LiveStream?>(null) }
    var timerFor by remember { mutableStateOf<LiveStream?>(null) }
    var folderMenu by remember { mutableStateOf<Category?>(null) }
    var unlockFor by remember { mutableStateOf<String?>(null) }
    val full = app.epg.ready

    // Ventana de 3 horas; al llegar al borde con ← → se mueve 2 horas, y ⏪ ⏩ cambian de día
    val nowSlot = halfHourFloor(System.currentTimeMillis())
    var windowStart by remember { mutableStateOf(nowSlot) }
    val minStart = if (full) nowSlot - 6 * 3_600_000L else nowSlot
    val maxStart = nowSlot + 4 * DAY_MS - WINDOW_MIN * 60_000L
    // Después de mover la ventana, el foco vuelve a la misma fila, a la hora indicada
    var focusTarget by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var focusedRow by remember { mutableStateOf(0) }
    fun shiftTo(start: Long, row: Int, at: Long) {
        val ns = start.coerceIn(minStart, maxStart)
        if (ns == windowStart) return
        windowStart = ns
        focusTarget = row to at.coerceIn(ns, ns + WINDOW_MIN * 60_000L - 60_000L)
    }

    // ---- Ventana con el canal en directo: solo cambia al pulsar OK, no al moverse por la parrilla ----
    var previewCh by remember {
        mutableStateOf(app.recents.items.firstOrNull()?.let {
            LiveStream(id = it.id, num = it.subtitle.toIntOrNull() ?: 0, name = it.title, icon = it.icon, categoryId = "", epgId = it.epgId)
        })
    }
    var previewError by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    started = true
                    // Al volver de pantalla completa, la ventana pone el último canal visto
                    app.recents.items.firstOrNull()?.let { r ->
                        if (previewCh?.id != r.id) previewCh = LiveStream(r.id, r.subtitle.toIntOrNull() ?: 0, r.title, r.icon, "", r.epgId)
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> started = false
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val previewAllowed = started && !com.visor.tv.player.MiniPlayer.active
    val preview = remember(previewAllowed) {
        if (!previewAllowed) null else {
            val audio = app.settings.audioLang.code
            val subs = app.settings.subsDefault.code
            val e = if (app.settings.engine == com.visor.tv.data.Engine.VLC) com.visor.tv.player.VlcEngine(app, audio, subs)
                else com.visor.tv.player.ExoEngine(app, app.http, audio, subs)
            e.setAspect(app.settings.aspect)
            PreviewEngine(e)
        }
    }
    androidx.compose.runtime.DisposableEffect(preview) { onDispose { preview?.release() } }
    LaunchedEffect(preview, previewCh?.id) {
        val ch = previewCh ?: return@LaunchedEffect
        val pe = preview ?: return@LaunchedEffect
        previewError = null
        pe.e.onError = { previewError = it }
        try { pe.e.prepare(app.repo.liveUrl(ch.id, app.settings.liveFormat.ext), 0, true) } catch (_: Exception) { }
    }

    /** OK sobre un canal: si ya está en la ventana, a pantalla completa; si no, a la ventana. */
    fun watch(list: List<LiveStream>, i: Int) {
        val s = list[i]
        if (previewCh?.id == s.id && preview != null) {
            preview.release() // libera la conexión antes de abrir el reproductor
            play(ctx, list.map { it.toPlay() }, i)
        } else {
            previewCh = s
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(start = 44.dp, end = 20.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || selected == SEARCH_FOLDER) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaFastForward -> { shiftTo(windowStart + DAY_MS, focusedRow, windowStart + DAY_MS); true }
                    Key.MediaRewind -> { shiftTo(windowStart - DAY_MS, focusedRow, windowStart - DAY_MS); true }
                    Key.MediaPlayPause, Key.MediaPlay -> { shiftTo(nowSlot, focusedRow, System.currentTimeMillis()); true }
                    else -> false
                }
            },
    ) {
        // ---- Arriba: ficha del programa enfocado y la ventana ----
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Column(Modifier.weight(1f).padding(end = 20.dp, top = 6.dp)) {
                val cur = info
                val e = cur?.second
                if (cur != null) {
                    T(
                        (if (e != null) dayLabel(e.start) + " · " else "") + (if (cur.first.num > 0) "${cur.first.num} · " else "") + cur.first.name,
                        Type.small.copy(fontWeight = FontWeight.SemiBold), Color(0xFF9A9DA3),
                    )
                    T(e?.title?.ifBlank { null } ?: "Sin guía", BIG_TITLE.copy(fontSize = 26.sp, lineHeight = 30.sp), Color.White, Modifier.padding(top = 2.dp), maxLines = 1)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e != null) {
                            T("${hm(e.start)} – ${hm(e.end)}", Type.small.copy(fontSize = 12.sp), Color(0xFFD6D6D0))
                            minutes(((e.end - e.start) / 60_000).toInt())?.let { T("· $it", Type.small.copy(fontSize = 12.sp), Color(0xFFD6D6D0)) }
                            val now = System.currentTimeMillis()
                            when {
                                e.isNow() -> {
                                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE5484D)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                        T("EN DIRECTO", Type.small.copy(fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold), Color.White)
                                    }
                                    T("quedan ${((e.end - now) / 60_000).coerceAtLeast(1)} min", Type.small.copy(fontSize = 12.sp), Color(0xFFD6D6D0))
                                }
                                e.end <= now -> T("Ya emitido", Type.small.copy(fontSize = 12.sp), Color(0xFF9A9DA3))
                                else -> T("OK para grabar", Type.small.copy(fontSize = 12.sp), Color(0xFF9A9DA3))
                            }
                        } else T("Este canal no tiene programación a esta hora. OK para verlo.", Type.small.copy(fontSize = 12.sp), Color(0xFFD6D6D0))
                    }
                    if (e != null && e.desc.isNotBlank()) T(e.desc, Type.small.copy(fontSize = 12.sp, lineHeight = 17.sp), Color(0xFFC4C6CA), Modifier.padding(top = 7.dp), maxLines = 2)
                } else {
                    T("Guía", BIG_TITLE.copy(fontSize = 26.sp, lineHeight = 30.sp), Color.White)
                    T("OK sobre un canal lo pone en la ventana; OK otra vez, a pantalla completa. ⏪ ⏩ cambian de día y ⏯ vuelve a ahora.",
                        Type.small.copy(fontSize = 12.sp, lineHeight = 17.sp), Color(0xFFC4C6CA), Modifier.padding(top = 6.dp), maxLines = 3)
                    if (!full) T(
                        app.epg.status ?: if (app.epg.updating) "Descargando la guía completa…" else "Sin guía completa: solo se ven las próximas horas.",
                        Type.small, Color(0xFF9A9DA3), Modifier.padding(top = 6.dp), maxLines = 2,
                    )
                }
            }
            // La ventana
            Box(
                Modifier.width(268.dp).height(151.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E0F11)),
                contentAlignment = Alignment.Center,
            ) {
                val ch = previewCh
                if (preview != null && ch != null) {
                    androidx.compose.runtime.key(preview) { preview.e.VideoSurface(Modifier.fillMaxSize()) }
                }
                when {
                    com.visor.tv.player.MiniPlayer.active -> T("Hay un canal en «Ver en ventana». Ciérralo para usar esta vista.", Type.small, Color(0xFF9A9DA3), Modifier.padding(14.dp), maxLines = 3)
                    ch == null -> T("Pulsa OK en un canal para verlo aquí", Type.small, Color(0xFF9A9DA3), Modifier.padding(14.dp), maxLines = 2)
                    previewError != null -> T("No se pudo poner ${ch.name}. Si estás grabando, tu cuenta puede no permitir otra conexión.", Type.small, Color(0xFFD6D6D0), Modifier.padding(14.dp), maxLines = 3)
                }
                if (ch != null) Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                    T("  ${ch.name} · en directo", Type.small.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), Color.White)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Carpetas en píldoras ----
        LoadView(cats) { list ->
            val chips = listOf(Category(SEARCH_FOLDER, "⌕ Buscar programa"), Category(FAV_FOLDER, "★ Favoritos"), Category(ALL_FOLDER, "Todos los canales")) +
                arrangedFolders(app, "live", list)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(chips, key = { _, c -> c.id }) { _, c ->
                    val own = c.id != SEARCH_FOLDER && c.id != FAV_FOLDER && c.id != ALL_FOLDER
                    val on = c.id == selected
                    Focusable(
                        onClick = { selected = c.id; if (own && app.folders.isBlocked("live", c.id)) unlockFor = c.id },
                        onFocused = { selected = c.id },
                        onMenu = if (own) ({ folderMenu = c }) else null,
                        shape = RoundedCornerShape(50),
                    ) { focused ->
                        Box(Modifier.background(if (focused) Color.White else if (on) Color.White.copy(alpha = 0.22f) else Color(0xFF16181C)).padding(horizontal = 13.dp, vertical = 6.dp)) {
                            T(if (own) folderLabel(app, "live", c) else c.name, Type.label, if (focused) Color.Black else if (on) Color.White else Color(0xFFBFC2C7))
                        }
                    }
                }
            }
            folderMenu?.let { c -> FolderMenu(app, FolderManage("live", list), c, onDismiss = { folderMenu = null }) }
        }
        unlockFor?.let { id -> UnlockFolderDialog(app, "live", id) { unlockFor = null } }
        Spacer(Modifier.height(10.dp))

        // ---- Parrilla ----
        when {
            selected == SEARCH_FOLDER -> ProgramSearch(app, onPick = { s, e ->
                if (e.isNow()) previewCh = s
                else if (e.start > System.currentTimeMillis()) dialog = s to e
            })
            isBlockedFolder(app, "live", selected) -> LockedPanel(app, "live", selected)
            else -> {
                val streams = load(selected, app.favorites.items.size, app.repo.version, app.folders.version, reset = true) {
                    kotlinx.coroutines.delay(FOLDER_DEBOUNCE_MS) // al recorrer carpetas deprisa no se pide cada una por el camino
                    when (selected) {
                        FAV_FOLDER -> app.favorites.of("live").map { it.toLive() }
                        ALL_FOLDER -> { val ex = app.folders.excludes("live"); app.repo.allLive().filterNot { ex(it.categoryId) } }
                        else -> app.repo.liveStreams(selected)
                    }
                }
                LoadView(streams) { list ->
                    if (list.isEmpty()) {
                        Empty(if (selected == FAV_FOLDER) "Aún no tienes canales favoritos. Pulsa ≡ sobre un canal para añadirlo." else "No hay canales en esta carpeta.")
                    } else BoxWithConstraints(Modifier.fillMaxSize()) {
                        val chanW = 170.dp
                        val timelineW = maxWidth - chanW - 8.dp
                        val dpPerMin = timelineW / WINDOW_MIN
                        val now = System.currentTimeMillis()
                        val nowIn = now in windowStart until windowStart + WINDOW_MIN * 60_000L
                        val nowX = dpPerMin * ((now - windowStart) / 60_000f)
                        Column {
                            // Cabecera: día y horas
                            Row(Modifier.fillMaxWidth().height(22.dp)) {
                                T(dayLabel(windowStart), Type.small.copy(fontWeight = FontWeight.SemiBold), Color(0xFF9A9DA3), Modifier.width(chanW + 8.dp))
                                Box(Modifier.width(timelineW)) {
                                    for (k in 0 until WINDOW_MIN / 30) {
                                        T(hm(windowStart + k * 30 * 60_000L), Type.small.copy(fontWeight = FontWeight.SemiBold), Color(0xFF9A9DA3), Modifier.offset(x = dpPerMin * (k * 30)))
                                    }
                                }
                            }
                            Box(Modifier.fillMaxSize()) {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                                        GuideRow(
                                            app, s, i, windowStart, chanW, timelineW, dpPerMin,
                                            isPreview = previewCh?.id == s.id,
                                            focusTarget = focusTarget,
                                            onTargetDone = { focusTarget = null },
                                            onFocus = { e -> info = s to e; focusedRow = i },
                                            onWatch = { watch(list, i) },
                                            onPick = { e ->
                                                if (e.start > System.currentTimeMillis()) dialog = s to e else watch(list, i)
                                            },
                                            onMenu = { menuFor = s },
                                            onEdge = { dir, at -> shiftTo(windowStart + dir * STEP_MS, i, at) },
                                        )
                                    }
                                }
                                // Línea de "ahora"
                                if (nowIn) Box(Modifier.offset(x = chanW + 8.dp + nowX).width(2.dp).fillMaxHeight().background(Color(0xFFE5484D)))
                            }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { s -> ChannelActions(app, s, onTimer = { timerFor = s }, onDismiss = { menuFor = null }) }
    timerFor?.let { s -> NewTimerDialog(app, onDismiss = { timerFor = null }, presetChannel = s) }
    dialog?.let { (s, e) ->
        val before = app.settings.marginBefore * 60_000L
        val after = app.settings.marginAfter * 60_000L
        ActionsDialog(
            title = e.title,
            subtitle = "${s.name} · ${dayHm(e.start)} – ${hm(e.end)}",
            actions = listOf(
                "Grabar este programa" to {
                    app.recordings.schedule(Recording(streamId = s.id, channel = s.name, title = e.title,
                        start = e.start - before, end = e.end + after, source = "epg"))
                },
                "Grabar todos los días a esta hora" to {
                    app.recordings.schedule(Recording(streamId = s.id, channel = s.name, title = e.title,
                        start = e.start - before, end = e.end + after, repeat = Recording.REPEAT_DAILY, source = "epg"))
                },
                "Grabar cada semana a esta hora" to {
                    app.recordings.schedule(Recording(streamId = s.id, channel = s.name, title = e.title,
                        start = e.start - before, end = e.end + after, repeat = Recording.REPEAT_WEEKLY, source = "epg"))
                },
                "Cancelar" to {},
            ),
            onDismiss = { dialog = null },
        )
    }
}

/** Buscar un programa por título en la guía completa ("¿cuándo echan el partido?"). */
@Composable
private fun ProgramSearch(app: VisorApp, onPick: (LiveStream, EpgEntry) -> Unit) {
    val p = LocalPalette.current
    if (!app.epg.ready) {
        Empty(
            if (app.epg.updating) "Descargando la guía completa… En cuanto termine podrás buscar programas."
            else "Para buscar programas hace falta la guía completa. Descárgala en Ajustes → Guía completa."
        )
        return
    }
    SearchPanel<Pair<LiveStream, EpgEntry>>(
        hint = "Buscar programa",
        source = { emptyList() },
        name = { it.second.title },
        remoteHint = "Escribe al menos 2 letras. Busca en la guía de todos tus canales, de ahora a 4 días vista.",
        remote = { text ->
            val ex = app.folders.excludes("live")
            val byEpg = app.repo.allLive().filterNot { ex(it.categoryId) }
                .filter { it.epgId.isNotBlank() }.groupBy { it.epgId.lowercase() }
            app.epg.search(text).mapNotNull { h -> byEpg[h.epgId]?.firstOrNull()?.let { it to h.entry } }
        },
    ) { found ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(found, key = { i, it -> "${it.first.id}:${it.second.start}:$i" }) { _, (s, e) ->
                val live = e.isNow()
                ListRow(
                    title = e.title,
                    subtitle = s.name + " · " + (if (live) "Ahora · hasta ${hm(e.end)}" else "${dayLabel(e.start)} ${hm(e.start)} – ${hm(e.end)}"),
                    trailing = if (live) "Ver" else "Grabar",
                    dot = if (live) p.rec else null,
                    onClick = { onPick(s, e) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    app: VisorApp,
    s: LiveStream,
    rowIndex: Int,
    windowStart: Long,
    chanW: androidx.compose.ui.unit.Dp,
    timelineW: androidx.compose.ui.unit.Dp,
    dpPerMin: androidx.compose.ui.unit.Dp,
    isPreview: Boolean,
    focusTarget: Pair<Int, Long>?,
    onTargetDone: () -> Unit,
    onFocus: (EpgEntry?) -> Unit,
    onWatch: () -> Unit,
    onPick: (EpgEntry) -> Unit,
    onMenu: () -> Unit,
    onEdge: (dir: Int, at: Long) -> Unit,
) {
    val windowEnd = windowStart + WINDOW_MIN * 60_000L
    val epg by produceState<List<EpgEntry>?>(null, s.id, windowStart, app.epg.version) {
        delay(200) // al desplazarse rápido no se piden guías que no se van a ver
        value = app.repo.epgRange(s.id, windowStart, windowEnd)
    }
    var rowFocused by remember { mutableStateOf(false) }
    val list = epg?.filter { it.end > windowStart && it.start < windowEnd }.orEmpty()
    val requesters = remember(list) { list.map { FocusRequester() } }
    val emptyReq = remember { FocusRequester() }

    // Tras mover la ventana con ← →, el foco vuelve a esta fila a la hora pedida
    LaunchedEffect(focusTarget, list, epg != null) {
        val t = focusTarget ?: return@LaunchedEffect
        if (t.first != rowIndex || epg == null) return@LaunchedEffect
        delay(30)
        val idx = list.indexOfFirst { t.second in it.start until it.end }.takeIf { it >= 0 }
            ?: list.indices.minByOrNull { kotlin.math.abs(list[it].start - t.second) }
        try { if (idx != null) requesters[idx].requestFocus() else emptyReq.requestFocus() } catch (_: Exception) { }
        onTargetDone()
    }

    fun edgeKeys(first: Boolean, last: Boolean, start: Long, end: Long) = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            e.key == Key.DirectionRight && last && end >= windowEnd -> { onEdge(1, windowEnd); true }
            e.key == Key.DirectionLeft && first && start <= windowStart -> { onEdge(-1, windowStart - 60_000L); true }
            else -> false
        }
    }

    Row(
        Modifier.fillMaxWidth().height(44.dp).onFocusChanged { rowFocused = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.width(chanW).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                .background(if (rowFocused) Color(0xFF24272C) else Color(0xFF121417)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T(if (s.num > 0) s.num.toString() else "", Type.small.copy(fontWeight = FontWeight.SemiBold), Color(0xFF9A9DA3), Modifier.width(22.dp))
            Img(s.icon, Modifier.size(width = 40.dp, height = 26.dp).clip(RoundedCornerShape(4.dp)), crop = false)
            T(s.name, Type.small.copy(fontWeight = FontWeight.SemiBold), Color.White, Modifier.weight(1f))
            if (isPreview) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE5484D)))
        }
        Box(Modifier.width(timelineW).fillMaxHeight()) {
            // Sin guía (o mientras carga) la fila sigue siendo enfocable: se puede bajar por ella y OK pone el canal
            if (list.isEmpty()) {
                Focusable(
                    onClick = onWatch,
                    onFocused = { onFocus(null) },
                    onMenu = onMenu,
                    focusRequester = emptyReq,
                    modifier = Modifier.fillMaxSize().then(edgeKeys(true, true, windowStart, windowEnd)),
                    shape = RoundedCornerShape(8.dp),
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) Color.White else Color(0xFF121417)).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        T(if (epg == null) "Cargando guía…" else if (focused) "Sin guía · OK para verlo" else "Sin guía",
                            Type.small, if (focused) Color.Black else Color(0xFF9A9DA3))
                    }
                }
            }
            val now = System.currentTimeMillis()
            list.forEachIndexed { idx, e ->
                val st = maxOf(e.start, windowStart)
                val en = minOf(e.end, windowEnd)
                val x = dpPerMin * ((st - windowStart) / 60_000f)
                val w = (dpPerMin * ((en - st) / 60_000f) - 5.dp).coerceAtLeast(12.dp)
                val past = e.end <= now
                val live = e.isNow()
                val scheduled = app.recordings.items.any {
                    it.streamId == s.id && it.status in listOf(Recording.STATUS_SCHEDULED, Recording.STATUS_RECORDING) &&
                        it.start < e.end && it.end > e.start
                }
                Focusable(
                    onClick = { onPick(e) },
                    onFocused = { onFocus(e) },
                    onMenu = onMenu,
                    focusRequester = requesters[idx],
                    modifier = Modifier.offset(x = x).width(w).fillMaxHeight()
                        .then(edgeKeys(idx == 0, idx == list.lastIndex, e.start, e.end)),
                    shape = RoundedCornerShape(8.dp),
                ) { focused ->
                    Box(Modifier.fillMaxSize().background(if (focused) Color.White else if (live) Color(0xFF1C1F24) else Color(0xFF121417))) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (scheduled) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                            T(e.title, Type.small.copy(fontWeight = FontWeight.SemiBold),
                                if (focused) Color.Black else if (past) Color(0xFF8A8D93) else Color(0xFFE6E6E2))
                        }
                        // Progreso de lo que se está emitiendo
                        if (live) {
                            val frac = ((now - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f)
                            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 5.dp).fillMaxWidth().height(2.dp)
                                .background(if (focused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.12f))) {
                                Box(Modifier.fillMaxWidth(frac).height(2.dp).background(Color(0xFFE5484D)))
                            }
                        }
                    }
                }
            }
        }
    }
}
