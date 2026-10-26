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

@Composable
fun GuideScreen(app: VisorApp) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val cats = load(app.repo.version) { app.repo.liveCategories() }
    var selected by rememberSaveable { mutableStateOf(FAV_FOLDER) }
    var info by remember { mutableStateOf<Pair<LiveStream, EpgEntry?>?>(null) }
    var dialog by remember { mutableStateOf<Pair<LiveStream, EpgEntry>?>(null) }
    val full = app.epg.ready

    // La ventana visible abarca 3 horas. Empieza en la media hora actual y se mueve de 2 en 2 horas
    // o de día en día. Con la guía completa se puede ir hasta 6 h atrás y 4 días adelante.
    val nowSlot = halfHourFloor(System.currentTimeMillis())
    var windowStart by remember { mutableStateOf(nowSlot) }
    val minStart = if (full) nowSlot - 6 * 3_600_000L else nowSlot
    val maxStart = nowSlot + 4 * DAY_MS - WINDOW_MIN * 60_000L
    fun shift(ms: Long) { windowStart = (windowStart + ms).coerceIn(minStart, maxStart) }

    val entry = remember { FolderEntry() }
    Row(
        Modifier
            .fillMaxSize()
            .enterAtFolders(entry)
            // ⏪ ⏩ del mando mueven la guía 2 horas
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || selected == SEARCH_FOLDER) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaFastForward -> { shift(STEP_MS); true }
                    Key.MediaRewind -> { shift(-STEP_MS); true }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LoadView(cats) { list ->
            FolderColumn(
                listOf(Category(SEARCH_FOLDER, "⌕ Buscar programa"), Category(FAV_FOLDER, "★ Favoritos")) + arrangedFolders(app, "live", list),
                selected, { selected = it }, width = 170.dp, entry = entry, manage = FolderManage("live", list),
            )
        }
        Column(Modifier.weight(1f)) {
            if (selected == SEARCH_FOLDER) {
                ProgramSearch(app, onPick = { s, e ->
                    if (e.isNow()) play(ctx, listOf(s.toPlay()), 0)
                    else if (e.start > System.currentTimeMillis()) dialog = s to e
                })
                return@Column
            }
            if (isBlockedFolder(app, "live", selected)) {
                LockedPanel(app, "live", selected)
                return@Column
            }
            // Ficha del programa enfocado
            Column(Modifier.fillMaxWidth().height(62.dp)) {
                val cur = info
                val e = cur?.second
                if (cur != null && e != null) {
                    T("${cur.first.name} · ${dayLabel(e.start)} ${hm(e.start)} – ${hm(e.end)}", Type.small, p.muted)
                    T(e.title, Type.h2, maxLines = 1)
                    T(e.desc, Type.small, p.muted, maxLines = 2)
                } else if (cur != null) {
                    T(cur.first.name, Type.small, p.muted)
                    T("Sin guía", Type.h2)
                    T("Este canal no tiene programación a esta hora. OK para verlo.", Type.small, p.muted, maxLines = 2)
                } else {
                    T("Elige un programa: OK para ver si está en emisión o para grabarlo si es futuro.", Type.small, p.muted, maxLines = 2)
                    if (!full) T(
                        app.epg.status ?: if (app.epg.updating) "Descargando la guía completa…" else "Sin guía completa: solo se ven las próximas horas.",
                        Type.small, p.muted, maxLines = 2,
                    )
                }
            }
            // Mover la ventana de horas
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("‹‹ Día", onClick = { shift(-DAY_MS) })
                TvButton("‹ 2 h", onClick = { shift(-STEP_MS) })
                TvButton("Ahora", selected = windowStart == nowSlot, onClick = { windowStart = nowSlot })
                TvButton("2 h ›", onClick = { shift(STEP_MS) })
                TvButton("Día ››", onClick = { shift(DAY_MS) })
                T("  ${dayLabel(windowStart)} · ${hm(windowStart)} – ${hm(windowStart + WINDOW_MIN * 60_000L)}", Type.label, p.text)
            }
            val streams = load(selected, app.favorites.items.size, app.repo.version, reset = true) {
                kotlinx.coroutines.delay(FOLDER_DEBOUNCE_MS) // al recorrer carpetas deprisa no se pide cada una por el camino
                if (selected == FAV_FOLDER) app.favorites.of("live").map { it.toLive() } else app.repo.liveStreams(selected)
            }
            LoadView(streams) { list ->
                if (list.isEmpty()) {
                    Empty("No hay canales en esta carpeta.")
                } else BoxWithConstraints(Modifier.fillMaxSize()) {
                    val chanW = 130.dp
                    val timelineW = maxWidth - chanW - 8.dp
                    val dpPerMin = timelineW / WINDOW_MIN
                    Column {
                        // Cabecera de horas
                        Row(Modifier.fillMaxWidth().height(20.dp)) {
                            Box(Modifier.width(chanW + 8.dp))
                            Box(Modifier.width(timelineW)) {
                                for (k in 0 until WINDOW_MIN / 30) {
                                    T(hm(windowStart + k * 30 * 60_000L), Type.small, p.muted, Modifier.offset(x = dpPerMin * (k * 30)))
                                }
                                val now = System.currentTimeMillis()
                                if (now in windowStart until windowStart + WINDOW_MIN * 60_000L) {
                                    val nowX = dpPerMin * ((now - windowStart) / 60_000f)
                                    Box(Modifier.offset(x = nowX).width(2.dp).height(20.dp).background(p.text))
                                }
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                                GuideRow(app, s, windowStart, chanW, timelineW, dpPerMin,
                                    onFocus = { e -> info = s to e },
                                    onPlay = { play(ctx, list.map { it.toPlay() }, i) },
                                    onPick = { e ->
                                        if (e.isNow()) play(ctx, list.map { it.toPlay() }, i)
                                        else if (e.start > System.currentTimeMillis()) dialog = s to e
                                    })
                            }
                        }
                    }
                }
            }
        }
    }

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
    windowStart: Long,
    chanW: androidx.compose.ui.unit.Dp,
    timelineW: androidx.compose.ui.unit.Dp,
    dpPerMin: androidx.compose.ui.unit.Dp,
    onFocus: (EpgEntry?) -> Unit,
    onPick: (EpgEntry) -> Unit,
    onPlay: () -> Unit,
) {
    val p = LocalPalette.current
    val windowEnd = windowStart + WINDOW_MIN * 60_000L
    val epg by produceState<List<EpgEntry>?>(null, s.id, windowStart, app.epg.version) {
        delay(200) // al desplazarse rápido no se piden guías que no se van a ver
        value = app.repo.epgRange(s.id, windowStart, windowEnd)
    }
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(chanW).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(p.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            T((if (s.num > 0) "${s.num}  " else "") + s.name, Type.small)
        }
        Box(Modifier.width(timelineW).fillMaxHeight()) {
            val list = epg?.filter { it.end > windowStart && it.start < windowEnd }.orEmpty()
            // Sin guía (o mientras carga) la fila sigue siendo enfocable: se puede bajar por ella y OK pone el canal
            if (list.isEmpty()) {
                Focusable(
                    onClick = onPlay,
                    onFocused = { onFocus(null) },
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(10.dp),
                ) { focused ->
                    Box(
                        Modifier.fillMaxSize().background(if (focused) p.focusBg else p.surface2).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        T(if (epg == null) "Cargando guía…" else if (focused) "Sin guía · OK para ver el canal" else "Sin guía",
                            Type.small, if (focused) p.focusText else p.muted)
                    }
                }
            }
            val now = System.currentTimeMillis()
            list.forEach { e ->
                val st = maxOf(e.start, windowStart)
                val en = minOf(e.end, windowEnd)
                val x = dpPerMin * ((st - windowStart) / 60_000f)
                val w = (dpPerMin * ((en - st) / 60_000f) - 4.dp).coerceAtLeast(12.dp)
                val past = e.end <= now
                val scheduled = app.recordings.items.any {
                    it.streamId == s.id && it.status in listOf(Recording.STATUS_SCHEDULED, Recording.STATUS_RECORDING) &&
                        it.start < e.end && it.end > e.start
                }
                Focusable(
                    onClick = { onPick(e) },
                    onFocused = { onFocus(e) },
                    modifier = Modifier.offset(x = x).width(w).fillMaxHeight(),
                    shape = RoundedCornerShape(10.dp),
                ) { focused ->
                    Row(
                        Modifier.fillMaxSize().background(if (focused) p.focusBg else if (e.isNow()) p.surface else p.surface2).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (scheduled) Box(Modifier.size(7.dp).clip(CircleShape).background(p.rec))
                        T(e.title, Type.small, if (focused) p.focusText else if (past) p.muted else p.text)
                    }
                }
            }
        }
    }
}
