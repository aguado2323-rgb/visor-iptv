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

private const val WINDOW_MIN = 180

@Composable
fun GuideScreen(app: VisorApp) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val cats = load(Unit) { app.repo.liveCategories() }
    var selected by rememberSaveable { mutableStateOf(FAV_FOLDER) }
    var info by remember { mutableStateOf<Pair<LiveStream, EpgEntry>?>(null) }
    var dialog by remember { mutableStateOf<Pair<LiveStream, EpgEntry>?>(null) }

    // La ventana empieza en la media hora anterior y abarca 3 horas.
    val windowStart = remember {
        val n = System.currentTimeMillis()
        n - (n % (30 * 60_000L))
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LoadView(cats) { list ->
            FolderColumn(listOf(Category(FAV_FOLDER, "★ Favoritos")) + list, selected, { selected = it }, width = 170.dp)
        }
        Column(Modifier.weight(1f)) {
            // Ficha del programa enfocado
            Column(Modifier.fillMaxWidth().height(70.dp)) {
                val cur = info
                if (cur != null) {
                    T("${cur.first.name} · ${hm(cur.second.start)} – ${hm(cur.second.end)}", Type.small, p.muted)
                    T(cur.second.title, Type.h2)
                    T(cur.second.desc, Type.small, p.muted, maxLines = 2)
                } else {
                    T("Elige un programa: OK para ver si está en emisión o para grabarlo si es futuro.", Type.small, p.muted, maxLines = 2)
                }
            }
            val streams = load(selected, app.favorites.items.size) {
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
                                val nowX = dpPerMin * ((System.currentTimeMillis() - windowStart) / 60_000f)
                                Box(Modifier.offset(x = nowX).width(2.dp).height(20.dp).background(p.text))
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                                GuideRow(app, s, windowStart, chanW, timelineW, dpPerMin,
                                    onFocus = { e -> info = s to e },
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

@Composable
private fun GuideRow(
    app: VisorApp,
    s: LiveStream,
    windowStart: Long,
    chanW: androidx.compose.ui.unit.Dp,
    timelineW: androidx.compose.ui.unit.Dp,
    dpPerMin: androidx.compose.ui.unit.Dp,
    onFocus: (EpgEntry) -> Unit,
    onPick: (EpgEntry) -> Unit,
) {
    val p = LocalPalette.current
    val windowEnd = windowStart + WINDOW_MIN * 60_000L
    val epg by produceState<List<EpgEntry>?>(null, s.id) {
        delay(250)
        value = app.repo.shortEpg(s.id, 12)
    }
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(chanW).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(p.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            T((if (s.num > 0) "${s.num}  " else "") + s.name, Type.small)
        }
        Box(Modifier.width(timelineW).fillMaxHeight()) {
            val list = epg?.filter { it.end > windowStart && it.start < windowEnd }.orEmpty()
            if (epg != null && list.isEmpty()) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(p.surface2).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    T("Sin guía", Type.small, p.muted)
                }
            }
            list.forEach { e ->
                val st = maxOf(e.start, windowStart)
                val en = minOf(e.end, windowEnd)
                val x = dpPerMin * ((st - windowStart) / 60_000f)
                val w = (dpPerMin * ((en - st) / 60_000f) - 4.dp).coerceAtLeast(12.dp)
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
                        T(e.title, Type.small, if (focused) p.focusText else p.text)
                    }
                }
            }
        }
    }
}
