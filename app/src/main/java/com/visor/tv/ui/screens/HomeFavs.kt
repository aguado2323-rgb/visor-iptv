package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.VodStream
import com.visor.tv.ui.Pill
import kotlinx.coroutines.delay
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
    val movies = load(Unit) { app.repo.latestVod(30) }
    val shows = load(Unit) { app.repo.latestSeries(30) }
    val active = app.recordings.items.count { it.status == com.visor.tv.record.Recording.STATUS_RECORDING }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        // 1. Canales favoritos
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("Tus canales", Type.h2)
                    if (active > 0) T("● Grabando $active", Type.small, p.rec)
                }
                if (chans.isEmpty()) Empty("Pulsa ≡ (Menú) sobre un canal en TV para tenerlo aquí.")
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    itemsIndexed(chans, key = { _, f -> f.key }) { i, f ->
                        ChannelTile(f, onClick = { play(ctx, chans.map { it.toLive().toPlay() }, i) }, onMenu = { app.favorites.toggle(f) })
                    }
                }
            }
        }
        // 2. Películas nuevas, en grande y rotando solas
        item { MoviesShowcase(app, nav, movies) }
        // 3. Series nuevas, con su descripción
        item { SeriesShelf(app, nav, shows) }
    }
}

/** Escaparate de películas nuevas: ficha grande arriba y fila de pósters debajo. */
@Composable
private fun MoviesShowcase(app: VisorApp, nav: Nav, state: Load<List<VodStream>>) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Películas nuevas", Type.h2)
        LoadView(state) { all ->
            val list = all.take(20)
            if (list.isEmpty()) Empty("Tu lista no tiene películas nuevas.") else MoviesShowcaseBody(app, nav, list)
        }
    }
}

@Composable
private fun MoviesShowcaseBody(app: VisorApp, nav: Nav, list: List<VodStream>) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var featured by remember { mutableIntStateOf(0) }
            var rowFocused by remember { mutableStateOf(false) }
            // Mientras no estés recorriendo la fila, la ficha cambia sola cada 8 segundos.
            LaunchedEffect(rowFocused, list.size) {
                if (!rowFocused) while (true) { delay(8000); featured = (featured + 1) % list.size }
            }
            val v = list[featured.coerceIn(0, list.lastIndex)]
            val info = load(v.id) { app.repo.vodInfo(v.id) }.orNull()

            // Ficha destacada
            Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(20.dp)).background(p.surface)) {
                Crossfade(targetState = info?.backdrop?.ifBlank { null } ?: info?.cover?.ifBlank { null } ?: v.icon, label = "fondo") { url ->
                    Box(Modifier.fillMaxSize()) {
                        Img(url, Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.CenterEnd))
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0.30f to p.surface, 0.62f to p.surface.copy(alpha = 0.55f), 1f to Color.Transparent)
                    )
                )
                Column(
                    Modifier.fillMaxHeight().fillMaxWidth(0.5f).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Pill("NUEVA", p.focusBg, p.focusText)
                    T(info?.name?.ifBlank { null } ?: v.name, Type.heading, maxLines = 2)
                    val meta = listOfNotNull(
                        info?.year?.ifBlank { null }, info?.duration?.ifBlank { null }, info?.genre?.ifBlank { null },
                        (info?.rating ?: v.rating).toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) T(meta, Type.small, p.muted)
                    T(info?.plot?.ifBlank { null } ?: "Cargando sinopsis…", Type.body, p.text, maxLines = 4)
                }
                // Puntos de posición
                Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.indices.forEach { i ->
                        Box(Modifier.size(if (i == featured) 14.dp else 5.dp, 5.dp).clip(CircleShape).background(if (i == featured) Color.White else Color.White.copy(alpha = 0.45f)))
                    }
                }
            }
            LazyRow(
                modifier = Modifier.onFocusChanged { rowFocused = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) {
                itemsIndexed(list, key = { _, m -> m.id }) { i, m ->
                    Poster(
                        url = m.icon, title = m.name, width = 92.dp,
                        onClick = { nav.push(Route.Movie(m)) },
                        onFocused = { featured = i },
                        onMenu = { app.favorites.toggle(m.toFav()) },
                    )
                }
            }
    }
}

/** Fila de series nuevas con carátula y descripción. */
@Composable
private fun SeriesShelf(app: VisorApp, nav: Nav, state: Load<List<SeriesItem>>) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Series nuevas", Type.h2)
        LoadView(state) { all ->
            val list = all.take(20)
            if (list.isEmpty()) Empty("Tu lista no tiene series nuevas.") else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                itemsIndexed(list, key = { _, s -> s.id }) { _, s ->
                    Focusable(
                        onClick = { nav.push(Route.Show(s)) },
                        onMenu = { app.favorites.toggle(s.toFav()) },
                        shape = RoundedCornerShape(16.dp),
                        scaleOnFocus = true,
                    ) { focused ->
                        Row(
                            Modifier.width(290.dp).height(124.dp).background(if (focused) p.focusBg else p.surface).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Img(s.cover, Modifier.width(70.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val fg = if (focused) p.focusText else p.text
                                T(s.name, Type.label, fg, maxLines = 2)
                                s.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { T("★ %.1f".format(it), Type.small, fg.copy(alpha = 0.7f)) }
                                T(s.plot.ifBlank { "Sin descripción del proveedor." }, Type.small, fg.copy(alpha = 0.75f), maxLines = 4)
                            }
                        }
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
