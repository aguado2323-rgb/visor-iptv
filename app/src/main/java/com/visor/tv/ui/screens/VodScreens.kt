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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
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

internal fun rating(r: String): String? = r.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }

// ---------------- Películas ----------------

@Composable
fun MoviesScreen(app: VisorApp, nav: Nav) {
    val cats = load(app.repo.version) { app.repo.vodCategories() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = remember { FolderEntry() }
    Row(Modifier.fillMaxSize().enterAtFolders(entry), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LoadView(cats) { list ->
            if (selected == null) selected = NEW_FOLDER
            FolderColumn(fixedFolders(withNew = true) + arrangedFolders(app, "vod", list), selected, { selected = it }, entry = entry, manage = FolderManage("vod", list))
        }
        val sel = selected
        Column(Modifier.weight(1f)) {
            when (sel) {
                null -> {}
                SEARCH_FOLDER -> SearchPanel("Buscar película", { val ex = app.folders.excludes("vod"); app.repo.allVod().filterNot { ex(it.categoryId) } }, { it.name }) { found -> MovieGrid(app, nav, found) }
                else -> if (isBlockedFolder(app, "vod", sel)) LockedPanel(app, "vod", sel) else {
                    val items = load(sel, app.favorites.items.size, app.repo.version, app.folders.version) {
                        when (sel) {
                            FAV_FOLDER -> app.favorites.of("vod").map { it.toVod() }
                            NEW_FOLDER -> app.repo.latestVod(30)
                            else -> app.repo.vodStreams(sel)
                        }
                    }
                    if (sel == NEW_FOLDER) T("Las 30 últimas películas añadidas a tu lista", Type.small, LocalPalette.current.muted, Modifier.padding(start = 6.dp, bottom = 8.dp))
                    else Row(Modifier.padding(start = 6.dp, bottom = 10.dp)) { SortButton(app.settings.vodSort) { app.settings.updateVodSort(it) } }
                    LoadView(items) { list ->
                        val sorted = remember(list, app.settings.vodSort, sel) { if (sel == NEW_FOLDER) list else list.sortedFor(app.settings.vodSort) }
                        if (sorted.isEmpty()) Empty("No hay películas aquí. Pulsa ≡ sobre una película para añadirla a favoritos.")
                        else MovieGrid(app, nav, sorted)
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
            val key = "vod:${v.id}"
            Poster(
                url = v.icon,
                title = v.name,
                subtitle = listOfNotNull(
                    if (app.favorites.contains("vod", v.id)) "★" else null,
                    v.year.takeIf { it > 0 }?.toString(),
                    rating(v.rating),
                ).joinToString("  "),
                onClick = { nav.open(Route.Movie(v), key) },
                onMenu = { app.favorites.toggle(v.toFav()) },
                focusRequester = rememberReturnFocus(nav, key),
            )
        }
    }
}

// ---------------- Series ----------------

@Composable
fun SeriesScreen(app: VisorApp, nav: Nav) {
    val cats = load(app.repo.version) { app.repo.seriesCategories() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = remember { FolderEntry() }
    Row(Modifier.fillMaxSize().enterAtFolders(entry), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LoadView(cats) { list ->
            if (selected == null) selected = NEW_FOLDER
            FolderColumn(fixedFolders(withNew = true) + arrangedFolders(app, "series", list), selected, { selected = it }, entry = entry, manage = FolderManage("series", list))
        }
        val sel = selected
        Column(Modifier.weight(1f)) {
            when (sel) {
                null -> {}
                SEARCH_FOLDER -> SearchPanel("Buscar serie", { val ex = app.folders.excludes("series"); app.repo.allSeries().filterNot { ex(it.categoryId) } }, { it.name }) { found -> SeriesGrid(app, nav, found) }
                else -> if (isBlockedFolder(app, "series", sel)) LockedPanel(app, "series", sel) else {
                    val items = load(sel, app.favorites.items.size, app.repo.version, app.folders.version) {
                        when (sel) {
                            FAV_FOLDER -> app.favorites.of("series").map { it.toSeries() }
                            NEW_FOLDER -> app.repo.latestSeries(30)
                            else -> app.repo.series(sel)
                        }
                    }
                    if (sel == NEW_FOLDER) T("Las 30 series añadidas o actualizadas más recientemente", Type.small, LocalPalette.current.muted, Modifier.padding(start = 6.dp, bottom = 8.dp))
                    else Row(Modifier.padding(start = 6.dp, bottom = 10.dp)) { SortButton(app.settings.seriesSort) { app.settings.updateSeriesSort(it) } }
                    LoadView(items) { list ->
                        val sorted = remember(list, app.settings.seriesSort, sel) { if (sel == NEW_FOLDER) list else list.sortedFor(app.settings.seriesSort) }
                        if (sorted.isEmpty()) Empty("No hay series aquí. Pulsa ≡ sobre una serie para añadirla a favoritos.")
                        else SeriesGrid(app, nav, sorted)
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
            val key = "series:${s.id}"
            Poster(
                url = s.cover,
                title = s.name,
                subtitle = listOfNotNull(
                    if (app.favorites.contains("series", s.id)) "★" else null,
                    s.year.takeIf { it > 0 }?.toString(),
                    rating(s.rating),
                ).joinToString("  "),
                onClick = { nav.open(Route.Show(s), key) },
                onMenu = { app.favorites.toggle(s.toFav()) },
                focusRequester = rememberReturnFocus(nav, key),
            )
        }
    }
}
