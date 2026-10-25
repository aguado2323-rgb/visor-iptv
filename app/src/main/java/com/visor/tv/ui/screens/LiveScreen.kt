package com.visor.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visor.tv.VisorApp
import com.visor.tv.data.Category
import com.visor.tv.data.EpgEntry
import com.visor.tv.data.FavItem
import com.visor.tv.data.LiveStream
import com.visor.tv.data.PlayItem
import com.visor.tv.play
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Img
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import kotlinx.coroutines.delay

fun LiveStream.toPlay() = PlayItem(kind = "live", id = id, title = name, subtitle = if (num > 0) num.toString() else "", icon = icon, epgId = epgId)
fun LiveStream.toFav() = FavItem(type = "live", id = id, name = name, icon = icon, epgId = epgId, num = num)
fun FavItem.toLive() = LiveStream(id = id, num = num, name = name, icon = icon, categoryId = "", epgId = epgId)

@Composable
fun LiveScreen(app: VisorApp) {
    val cats = load(Unit) { app.repo.liveCategories() }
    var selected by rememberSaveable { mutableStateOf(FAV_FOLDER) }
    var menuFor by remember { mutableStateOf<LiveStream?>(null) }
    var timerFor by remember { mutableStateOf<LiveStream?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LoadView(cats) { list ->
            FolderColumn(fixedFolders(withNew = false) + list, selected, { selected = it })
        }
        Column(Modifier.weight(1f)) {
            if (selected == SEARCH_FOLDER) {
                SearchPanel("Buscar canal", { app.repo.allLive() }, { it.name }) { found ->
                    ChannelList(app, found, onMenu = { menuFor = it })
                }
            } else {
                val streams = load(selected, app.favorites.items.size) {
                    if (selected == FAV_FOLDER) app.favorites.of("live").map { it.toLive() } else app.repo.liveStreams(selected)
                }
                T("OK para ver · ≡ Menú para favoritos y grabar", Type.small, LocalPalette.current.muted, Modifier.padding(start = 12.dp, bottom = 6.dp))
                LoadView(streams) { list ->
                    if (list.isEmpty()) {
                        Empty(if (selected == FAV_FOLDER) "Aún no tienes canales favoritos. Pulsa ≡ sobre un canal para añadirlo." else "Esta carpeta está vacía.")
                    } else {
                        ChannelList(app, list, onMenu = { menuFor = it })
                    }
                }
            }
        }
    }

    menuFor?.let { s ->
        ChannelActions(app, s, onTimer = { timerFor = s }, onDismiss = { menuFor = null })
    }
    timerFor?.let { s ->
        TimerDialog(app, s.id, s.name, onDismiss = { timerFor = null })
    }
}

@Composable
fun ChannelList(app: VisorApp, list: List<LiveStream>, onMenu: (LiveStream) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            val now by produceState<EpgEntry?>(null, s.id) {
                delay(350) // al desplazarse rápido no se piden guías que no se van a ver
                value = app.repo.shortEpg(s.id, 3).firstOrNull { it.isNow() }
            }
            val rec = app.recordings.activeFor(s.id) != null
            ListRow(
                title = (if (s.num > 0) "${s.num}  " else "") + s.name,
                subtitle = now?.let { "${it.title} · hasta ${hm(it.end)}" },
                trailing = if (app.favorites.contains("live", s.id)) "★" else null,
                dot = if (rec) LocalPalette.current.rec else null,
                leading = { Img(s.icon, Modifier.size(width = 44.dp, height = 30.dp).clip(RoundedCornerShape(6.dp)), crop = false) },
                onClick = { play(ctx, list.map { it.toPlay() }, i) },
                onMenu = { onMenu(s) },
            )
        }
    }
}

@Composable
fun ChannelActions(app: VisorApp, s: LiveStream, onTimer: () -> Unit, onDismiss: () -> Unit) {
    val isFav = app.favorites.contains("live", s.id)
    val active = app.recordings.activeFor(s.id)
    val actions = buildList<Pair<String, () -> Unit>> {
        add((if (isFav) "Quitar de favoritos" else "Añadir a favoritos") to { app.favorites.toggle(s.toFav()); Unit })
        if (active == null) {
            add("Grabar ahora (2 horas)" to { startManualRecording(app, s.id, s.name, null) })
        } else {
            add("Detener grabación" to { app.recordings.cancel(active.id) })
        }
        add("Programar grabación…" to onTimer)
    }
    ActionsDialog(s.name, actions, onDismiss)
}

/** Grabación inmediata. Si hay EPG, termina con el programa actual (más el margen). */
fun startManualRecording(app: VisorApp, streamId: Int, channel: String, epgNow: EpgEntry?) {
    val now = System.currentTimeMillis()
    val end = epgNow?.end?.plus(app.settings.marginAfter * 60_000L) ?: (now + 2 * 60 * 60_000L)
    app.recordings.schedule(
        com.visor.tv.record.Recording(
            streamId = streamId, channel = channel, title = epgNow?.title ?: channel,
            start = now, end = end, source = "manual",
        )
    )
}
