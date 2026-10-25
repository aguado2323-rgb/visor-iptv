package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.ArtSource
import com.visor.tv.data.Category
import com.visor.tv.data.FavItem
import com.visor.tv.data.PlayItem
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.TmdbArt
import com.visor.tv.data.VodStream
import com.visor.tv.play
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Img
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.Poster
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type

fun VodStream.toFav() = FavItem(type = "vod", id = id, name = name, icon = icon, ext = ext)
fun FavItem.toVod() = VodStream(id = id, name = name, icon = icon, rating = "", ext = ext, categoryId = "")
fun SeriesItem.toFav() = FavItem(type = "series", id = id, name = name, icon = cover)
fun FavItem.toSeries() = SeriesItem(id = id, name = name, cover = icon, rating = "", plot = "", categoryId = "")

private fun rating(r: String): String? = r.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }

// ---------------- Películas ----------------

@Composable
fun MoviesScreen(app: VisorApp, nav: Nav) {
    val cats = load(app.repo.version) { app.repo.vodCategories() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LoadView(cats) { list ->
            if (selected == null) selected = NEW_FOLDER
            FolderColumn(fixedFolders(withNew = true) + list, selected, { selected = it })
        }
        val sel = selected
        Column(Modifier.weight(1f)) {
            when (sel) {
                null -> {}
                SEARCH_FOLDER -> SearchPanel("Buscar película", { app.repo.allVod() }, { it.name }) { found -> MovieGrid(app, nav, found) }
                else -> {
                    val items = load(sel, app.favorites.items.size, app.repo.version) {
                        when (sel) {
                            FAV_FOLDER -> app.favorites.of("vod").map { it.toVod() }
                            NEW_FOLDER -> app.repo.latestVod(30)
                            else -> app.repo.vodStreams(sel)
                        }
                    }
                    if (sel == NEW_FOLDER) T("Las 30 últimas películas añadidas a tu lista", Type.small, LocalPalette.current.muted, Modifier.padding(start = 6.dp, bottom = 8.dp))
                    LoadView(items) { list ->
                        if (list.isEmpty()) Empty("No hay películas aquí. Pulsa ≡ sobre una película para añadirla a favoritos.")
                        else MovieGrid(app, nav, list)
                    }
                }
            }
        }
    }
}

@Composable
fun MovieGrid(app: VisorApp, nav: Nav, list: List<VodStream>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        items(list, key = { it.id }) { v ->
            Poster(
                url = v.icon,
                title = v.name,
                subtitle = (if (app.favorites.contains("vod", v.id)) "★ Favorita  " else "") + (rating(v.rating) ?: ""),
                onClick = { nav.push(Route.Movie(v)) },
                onMenu = { app.favorites.toggle(v.toFav()) },
            )
        }
    }
}

@Composable
fun MovieDetail(app: VisorApp, vod: VodStream) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val info = load(vod.id) { app.repo.vodInfo(vod.id) }
    val useTmdb = app.settings.artSource == ArtSource.TMDB && app.tmdb.available
    val tmdb = load(vod.id, useTmdb, info.orNull()?.tmdbId) {
        val id = info.orNull()?.tmdbId.orEmpty()
        if (useTmdb) app.tmdb.art(id, isTv = false) else null
    }
    val art: TmdbArt? = tmdb.orNull()
    val playBtn = remember { FocusRequester() }
    val isFav = app.favorites.contains("vod", vod.id)
    val resume = app.progress.positionOf("vod:${vod.id}")

    Box(Modifier.fillMaxSize().background(p.bg)) {
        val i = info.orNull()
        val backdrop = art?.backdrop?.ifBlank { null } ?: i?.backdrop.orEmpty()
        if (backdrop.isNotBlank()) {
            Img(backdrop, Modifier.fillMaxWidth(0.62f).aspectRatio(16f / 9f).align(androidx.compose.ui.Alignment.TopEnd))
            Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = 0.55f)))
        }
        Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Img(
                art?.poster?.ifBlank { null } ?: i?.cover?.ifBlank { null } ?: vod.icon,
                Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Películas", Type.small, p.muted)
                T(i?.name?.ifBlank { null } ?: vod.name, Type.title, maxLines = 2)
                val meta = listOfNotNull(
                    i?.year?.ifBlank { null }, i?.duration?.ifBlank { null }, i?.genre?.ifBlank { null },
                    (art?.rating?.ifBlank { null }?.let { "★ $it (TMDB)" }) ?: rating(i?.rating ?: vod.rating),
                ).joinToString(" · ")
                if (meta.isNotBlank()) T(meta, Type.body, p.muted)
                val plot = art?.overview?.ifBlank { null } ?: i?.plot.orEmpty()
                if (plot.isNotBlank()) T(plot, Type.body, p.text, maxLines = 6)
                if (!i?.director.isNullOrBlank()) T("Dirección: ${i?.director}", Type.small, p.muted)
                if (!i?.cast.isNullOrBlank()) T("Reparto: ${i?.cast}", Type.small, p.muted, maxLines = 2)
                if (info is Load.Err) T("No se pudo cargar la ficha completa.", Type.small, p.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ext = i?.ext ?: vod.ext
                    val item = PlayItem(kind = "vod", id = vod.id, ext = ext, title = vod.name, icon = vod.icon)
                    TvButton(if (resume > 0) "Continuar" else "Reproducir", focusRequester = playBtn, onClick = { play(ctx, listOf(item), 0) })
                    if (resume > 0) TvButton("Desde el principio", onClick = {
                        app.progress.update(item, 0, 1); play(ctx, listOf(item), 0)
                    })
                    TvButton(if (isFav) "★ En favoritos" else "☆ Añadir a favoritos", selected = isFav, onClick = { app.favorites.toggle(vod.toFav()) })
                }
            }
        }
    }
    LaunchedEffect(Unit) { try { playBtn.requestFocus() } catch (_: Exception) { } }
}

// ---------------- Series ----------------

@Composable
fun SeriesScreen(app: VisorApp, nav: Nav) {
    val cats = load(app.repo.version) { app.repo.seriesCategories() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LoadView(cats) { list ->
            if (selected == null) selected = NEW_FOLDER
            FolderColumn(fixedFolders(withNew = true) + list, selected, { selected = it })
        }
        val sel = selected
        Column(Modifier.weight(1f)) {
            when (sel) {
                null -> {}
                SEARCH_FOLDER -> SearchPanel("Buscar serie", { app.repo.allSeries() }, { it.name }) { found -> SeriesGrid(app, nav, found) }
                else -> {
                    val items = load(sel, app.favorites.items.size, app.repo.version) {
                        when (sel) {
                            FAV_FOLDER -> app.favorites.of("series").map { it.toSeries() }
                            NEW_FOLDER -> app.repo.latestSeries(30)
                            else -> app.repo.series(sel)
                        }
                    }
                    if (sel == NEW_FOLDER) T("Las 30 series añadidas o actualizadas más recientemente", Type.small, LocalPalette.current.muted, Modifier.padding(start = 6.dp, bottom = 8.dp))
                    LoadView(items) { list ->
                        if (list.isEmpty()) Empty("No hay series aquí. Pulsa ≡ sobre una serie para añadirla a favoritos.")
                        else SeriesGrid(app, nav, list)
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesGrid(app: VisorApp, nav: Nav, list: List<SeriesItem>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        items(list, key = { it.id }) { s ->
            Poster(
                url = s.cover,
                title = s.name,
                subtitle = (if (app.favorites.contains("series", s.id)) "★ Favorita  " else "") + (rating(s.rating) ?: ""),
                onClick = { nav.push(Route.Show(s)) },
                onMenu = { app.favorites.toggle(s.toFav()) },
            )
        }
    }
}

@Composable
fun SeriesDetail(app: VisorApp, series: SeriesItem) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val info = load(series.id) { app.repo.seriesInfo(series.id) }
    val useTmdb = app.settings.artSource == ArtSource.TMDB && app.tmdb.available
    val tmdb = load(series.id, useTmdb, info.orNull()?.tmdbId) {
        val id = info.orNull()?.tmdbId.orEmpty()
        if (useTmdb) app.tmdb.art(id, isTv = true) else null
    }
    val art = tmdb.orNull()
    var season by remember { mutableIntStateOf(-1) }
    val isFav = app.favorites.contains("series", series.id)

    Row(Modifier.fillMaxSize().background(p.bg).padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        val i = info.orNull()
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Img(art?.poster?.ifBlank { null } ?: i?.cover?.ifBlank { null } ?: series.cover,
                Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
            T("Series", Type.small, p.muted)
            T(i?.name?.ifBlank { null } ?: series.name, Type.heading, maxLines = 2)
            val meta = listOfNotNull(i?.year?.ifBlank { null }, i?.genre?.ifBlank { null },
                art?.rating?.ifBlank { null }?.let { "★ $it (TMDB)" } ?: rating(i?.rating ?: series.rating)).joinToString(" · ")
            if (meta.isNotBlank()) T(meta, Type.small, p.muted, maxLines = 2)
            T(art?.overview?.ifBlank { null } ?: i?.plot?.ifBlank { null } ?: series.plot, Type.small, p.text, maxLines = 7)
            TvButton(if (isFav) "★ En favoritos" else "☆ Añadir a favoritos", selected = isFav, onClick = { app.favorites.toggle(series.toFav()) })
        }
        Column(Modifier.weight(1f)) {
            LoadView(info) { si ->
                if (si.seasons.isEmpty()) {
                    Empty("El proveedor no tiene episodios para esta serie.")
                } else {
                    val seasons = si.seasons.keys.toList()
                    if (season !in seasons) season = seasons.first()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            TvButton("Temporada $s", selected = s == season, onClick = { season = s }, onFocused = { season = s })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val eps = si.seasons[season].orEmpty()
                    val queue = eps.map {
                        PlayItem(kind = "episode", id = it.id, ext = it.ext, title = "T$season · E${it.num}  ${it.title}",
                            subtitle = series.name, icon = it.image.ifBlank { series.cover }, seriesId = series.id)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(eps, key = { _, e -> e.id }) { idx, e ->
                            val pos = app.progress.positionOf("episode:${e.id}")
                            ListRow(
                                title = "E${e.num} · ${e.title}",
                                subtitle = listOfNotNull(e.duration.ifBlank { null }, if (pos > 0) "a medias" else null, e.plot.ifBlank { null }).joinToString(" · "),
                                leading = { Img(e.image.ifBlank { series.cover }, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) },
                                onClick = { play(ctx, queue, idx) },
                            )
                        }
                    }
                }
            }
        }
    }
}
