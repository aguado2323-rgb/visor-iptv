package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.FavItem
import com.visor.tv.data.LiveStream
import com.visor.tv.play
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.Img
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.Poster
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type

/** Tarjeta de canal para las baldas de favoritos. */
@Composable
fun ChannelTile(f: FavItem, onClick: () -> Unit, onMenu: () -> Unit) {
    val p = LocalPalette.current
    Focusable(onClick = onClick, onMenu = onMenu, shape = RoundedCornerShape(16.dp), scaleOnFocus = true) { focused ->
        Column(
            Modifier.width(132.dp).height(84.dp).background(if (focused) p.focusBg else p.surface).padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Img(f.icon, Modifier.size(width = 36.dp, height = 24.dp).clip(RoundedCornerShape(4.dp)), crop = false)
                if (f.num > 0) T(f.num.toString(), Type.small, if (focused) p.focusText else p.muted)
            }
            T(f.name, Type.label, if (focused) p.focusText else p.text, maxLines = 2)
        }
    }
}

@Composable
fun HomeScreen(app: VisorApp, nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val chans = app.favorites.of("live")
    val movies = app.favorites.of("vod")
    val shows = app.favorites.of("series")
    val cont = app.progress.entries.toList()
    val active = app.recordings.items.count { it.status == com.visor.tv.record.Recording.STATUS_RECORDING }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (active > 0) item { T("● Grabando $active ${if (active == 1) "canal" else "canales"} ahora", Type.label, p.rec) }
        if (cont.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Seguir viendo", Type.h2)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(cont, key = { _, e -> e.item.key }) { _, e ->
                        Poster(
                            url = e.item.icon, title = e.item.title, subtitle = e.item.subtitle.ifBlank { null },
                            progress = e.positionMs.toFloat() / e.durationMs.coerceAtLeast(1),
                            onClick = { play(ctx, listOf(e.item), 0) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Canales favoritos", Type.h2)
                if (chans.isEmpty()) Empty("Pulsa ≡ (Menú) sobre un canal para añadirlo aquí.")
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(chans, key = { _, f -> f.key }) { i, f ->
                        ChannelTile(f, onClick = { play(ctx, chans.map { it.toLive().toPlay() }, i) }, onMenu = { app.favorites.toggle(f) })
                    }
                }
            }
        }
        if (movies.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Películas favoritas", Type.h2)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(movies, key = { _, f -> f.key }) { _, f ->
                        Poster(f.icon, f.name, onClick = { nav.push(Route.Movie(f.toVod())) }, onMenu = { app.favorites.toggle(f) })
                    }
                }
            }
        }
        if (shows.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Series favoritas", Type.h2)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(shows, key = { _, f -> f.key }) { _, f ->
                        Poster(f.icon, f.name, onClick = { nav.push(Route.Show(f.toSeries())) }, onMenu = { app.favorites.toggle(f) })
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(app: VisorApp, nav: Nav) {
    var section by rememberSaveable { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<LiveStream?>(null) }
    var timerFor by remember { mutableStateOf<LiveStream?>(null) }
    val labels = listOf("Canales", "Películas", "Series")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l ->
                val n = app.favorites.of(listOf("live", "vod", "series")[i]).size
                TvButton("$l ($n)", selected = section == i, onClick = { section = i }, onFocused = { section = i })
            }
        }
        when (section) {
            0 -> {
                val list = app.favorites.of("live").map { it.toLive() }
                if (list.isEmpty()) Empty("Sin canales favoritos. En TV, pulsa ≡ sobre un canal.")
                else ChannelList(app, list, onMenu = { menuFor = it })
            }
            1 -> {
                val list = app.favorites.of("vod").map { it.toVod() }
                if (list.isEmpty()) Empty("Sin películas favoritas. En Películas, pulsa ≡ sobre una película.")
                else MovieGrid(app, nav, list)
            }
            else -> {
                val list = app.favorites.of("series").map { it.toSeries() }
                if (list.isEmpty()) Empty("Sin series favoritas. En Series, pulsa ≡ sobre una serie.")
                else SeriesGrid(app, nav, list)
            }
        }
    }
    menuFor?.let { s -> ChannelActions(app, s, onTimer = { timerFor = s }, onDismiss = { menuFor = null }) }
    timerFor?.let { s -> TimerDialog(app, s.id, s.name, onDismiss = { timerFor = null }) }
}
