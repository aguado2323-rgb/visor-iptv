package com.visor.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.displayTitle
import com.visor.tv.data.ArtSource
import com.visor.tv.data.PlayItem
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.VodStream
import com.visor.tv.play
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.Img
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.OledPalette
import com.visor.tv.ui.Poster
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Inicio de cine: arriba una portada a pantalla completa con las novedades (películas y series, rotando);
// al bajar, el fondo pasa a ser lo que tengas seleccionado y las filas ocupan la pantalla.
// Siempre en oscuro, sea cual sea el tema.

/** Lo que se pinta arriba: fondo, título y datos de lo destacado o de lo seleccionado. */
private data class HomeArt(
    val key: String,
    val title: String,
    val meta: String,
    val plot: String,
    val backdrop: String?,
    val poster: String,
    val logo: String? = null,
    val age: String? = null,
    val kind: String = "",
)

private suspend fun <T> safe(block: suspend () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun quickArt(v: VodStream) = HomeArt(
    key = "mv:${v.id}", title = displayTitle(v.name),
    meta = listOfNotNull("Película", v.year.takeIf { it > 0 }?.toString(), rating(v.rating)).joinToString("  ·  "),
    plot = "", backdrop = null, poster = v.icon, kind = "PELÍCULA",
)

private fun quickArt(s: SeriesItem) = HomeArt(
    key = "sr:${s.id}", title = displayTitle(s.name),
    meta = listOfNotNull("Serie", s.year.takeIf { it > 0 }?.toString(), rating(s.rating)).joinToString("  ·  "),
    plot = s.plot, backdrop = null, poster = s.cover, kind = "SERIE",
)

private fun quickArt(c: ContinueCard) = HomeArt(
    key = "cw:${c.key}", title = displayTitle(c.title), meta = c.short, plot = c.plot,
    backdrop = c.image.takeIf { c.episode != null || it != c.poster }, poster = c.poster.ifBlank { c.image },
)

/** Ficha completa de una película para la portada: datos del proveedor y, si hay, de TMDB. */
private suspend fun movieArt(app: VisorApp, v: VodStream): HomeArt {
    val i = safe { app.repo.vodInfo(v.id) }
    val tm = safe { app.tmdb.movie(i?.tmdbId, i?.name?.ifBlank { null } ?: v.name, i?.year?.take(4)?.toIntOrNull() ?: v.year) }
    val tf = app.settings.artSource == ArtSource.TMDB
    val year = i?.year?.ifBlank { null }?.take(4) ?: v.year.takeIf { it > 0 }?.toString()
    val dur = pick(tf, i?.duration?.ifBlank { null }?.let { fmtDuration(it) }, minutes(tm?.runtimeMin ?: 0))
    val stars = rating(i?.rating ?: v.rating) ?: tm?.rating?.ifBlank { null }?.let { "★ $it" }
    return HomeArt(
        key = "mv:${v.id}",
        title = displayTitle(i?.name?.ifBlank { null } ?: v.name),
        meta = listOfNotNull(year, dur, pick(tf, i?.genre, tm?.genres), stars).joinToString("  ·  "),
        plot = pick(tf, i?.plot, tm?.overview).orEmpty(),
        backdrop = pick(tf, i?.backdrop, tm?.backdrop),
        poster = pick(tf, i?.cover, tm?.poster) ?: v.icon,
        logo = tm?.logo?.ifBlank { null },
        age = pick(tf, i?.age, tm?.certification),
        kind = "PELÍCULA",
    )
}

/** Ficha completa de una serie para la portada. */
private suspend fun seriesArt(app: VisorApp, s: SeriesItem): HomeArt {
    val si = safe { app.repo.seriesInfo(s.id) }
    val tm = safe { app.tmdb.tv(si?.tmdbId, si?.name?.ifBlank { null } ?: s.name, si?.year?.take(4)?.toIntOrNull() ?: s.year) }
    val tf = app.settings.artSource == ArtSource.TMDB
    val n = si?.seasons?.size ?: 0
    val stars = rating(si?.rating ?: s.rating) ?: tm?.rating?.ifBlank { null }?.let { "★ $it" }
    return HomeArt(
        key = "sr:${s.id}",
        title = displayTitle(si?.name?.ifBlank { null } ?: s.name),
        meta = listOfNotNull(
            si?.year?.ifBlank { null }?.take(4) ?: s.year.takeIf { it > 0 }?.toString(),
            pick(tf, si?.genre, tm?.genres),
            if (n > 0) "$n ${if (n == 1) "temporada" else "temporadas"}" else null,
            stars,
        ).joinToString("  ·  "),
        plot = pick(tf, si?.plot?.ifBlank { null } ?: s.plot, tm?.overview).orEmpty(),
        backdrop = pick(tf, si?.backdrop, tm?.backdrop),
        poster = pick(tf, si?.cover, tm?.poster) ?: s.cover,
        logo = tm?.logo?.ifBlank { null },
        age = tm?.certification?.ifBlank { null },
        kind = "SERIE",
    )
}

/** Fondo a pantalla completa. Si solo hay carátula vertical, va a la derecha a toda altura. */
@Composable
private fun HomeBackdrop(art: HomeArt?, lowerFrac: Float) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = art?.backdrop ?: art?.poster.orEmpty(), animationSpec = tween(450), label = "fondo") { url ->
            Box(Modifier.fillMaxSize()) {
                if (url.isNotBlank()) {
                    if (art?.backdrop != null && url == art.backdrop) Img(url, Modifier.fillMaxSize())
                    else Img(url, Modifier.fillMaxHeight().aspectRatio(2f / 3f).align(Alignment.CenterEnd))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.34f to Color.Black.copy(alpha = 0.62f), 0.64f to Color.Transparent)))
        // Arriba el degradado empieza más abajo (portada); al bajar sube, para que las filas se lean
        val a = 0.45f - 0.10f * lowerFrac
        val b = 0.72f - 0.10f * lowerFrac
        val c = 0.88f - 0.08f * lowerFrac
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(a to Color.Transparent, b to Color.Black.copy(alpha = 0.82f), c to Color.Black)))
        // Sombra para que el menú de arriba se lea sobre cualquier imagen
        Box(Modifier.fillMaxWidth().height(90.dp).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)))
    }
}

/** Botón secundario en píldora ("Más información"). */
@Composable
private fun PillAction(text: String, canFocus: Boolean, focusRequester: FocusRequester? = null, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    Focusable(
        onClick = onClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(50),
        scaleOnFocus = true,
        modifier = Modifier.focusProperties { this.canFocus = canFocus },
    ) { focused ->
        Box(Modifier.background(if (focused) Color.White else Color.White.copy(alpha = 0.16f)).padding(horizontal = 18.dp, vertical = 10.dp)) {
            T(text, Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), if (focused) Color.Black else Color.White)
        }
    }
}

@Composable
fun HomeScreen(app: VisorApp, nav: Nav) = CompositionLocalProvider(LocalPalette provides OledPalette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val movies = load(app.repo.version, app.folders.version) { app.repo.latestVod(20) }.orNull().orEmpty()
    val shows = load(app.repo.version, app.folders.version) { app.repo.latestSeries(20) }.orNull().orEmpty()
    val cont = load(app.progress.entries.toList(), app.folders.version) { buildContinue(app) }.orNull().orEmpty()

    // Portada: novedades de películas y series alternadas
    val featured: List<Any> = remember(movies, shows) {
        val m = movies.take(5); val s = shows.take(5)
        buildList { for (i in 0 until maxOf(m.size, s.size)) { m.getOrNull(i)?.let(::add); s.getOrNull(i)?.let(::add) } }
    }
    var featIdx by rememberSaveable { mutableIntStateOf(0) }
    var lower by rememberSaveable { mutableStateOf(false) }
    var lastKey by remember { mutableLongStateOf(0L) }
    var btnIdx by remember { mutableIntStateOf(0) }
    val playBtn = remember { FocusRequester() }
    // Fila en la que estás: abajo solo se ve esa; al bajar o subir se sustituye por la siguiente
    var focusedRow by rememberSaveable { mutableIntStateOf(0) }
    val rowFocus = remember { mapOf("cw" to FocusRequester(), "mv" to FocusRequester(), "sr" to FocusRequester()) }
    var focusItem by remember { mutableStateOf<Any?>(null) }

    val cur = featured.getOrNull(featIdx.coerceIn(0, (featured.size - 1).coerceAtLeast(0)))
    val curKey = when (cur) { is VodStream -> "mv:${cur.id}"; is SeriesItem -> "sr:${cur.id}"; else -> "" }
    val heroArt = load(curKey) {
        when (cur) { is VodStream -> movieArt(app, cur); is SeriesItem -> seriesArt(app, cur); else -> null }
    }.orNull()?.takeIf { it.key == curKey } ?: when (cur) { is VodStream -> quickArt(cur); is SeriesItem -> quickArt(cur); else -> null }

    // Lo seleccionado en las filas: primero lo que ya se sabe; al pararse encima, la ficha completa
    val quick = when (val f = focusItem) { is VodStream -> quickArt(f); is SeriesItem -> quickArt(f); is ContinueCard -> quickArt(f); else -> null }
    val full = load(quick?.key) {
        delay(300)
        when (val f = focusItem) {
            is VodStream -> movieArt(app, f)
            is SeriesItem -> seriesArt(app, f)
            is ContinueCard -> quickArt(f)
            else -> null
        }
    }.orNull()?.takeIf { it.key == quick?.key }
    val rowArt = full ?: quick
    val art = if (lower) rowArt ?: heroArt else heroArt

    // Rota sola cada 9 s mientras se ve la portada y no se está usando el mando
    LaunchedEffect(featured.size, lower) {
        while (!lower && featured.size > 1) {
            delay(9000)
            if (System.currentTimeMillis() - lastKey > 12_000) featIdx = (featIdx + 1) % featured.size
        }
    }

    fun backToTop() {
        lower = false
        focusedRow = 0
        scope.launch {
            delay(60)
            try { playBtn.requestFocus() } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = lower) { backToTop() }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val lowerFrac by animateFloatAsState(if (lower) 1f else 0f, tween(350), label = "abajo")

    Box(Modifier.fillMaxSize()) {
        HomeBackdrop(art, lowerFrac)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenH = maxHeight
            val rowsTop = 204.dp

            // ---- Portada ----
            if (cur != null && heroArt != null) {
                Column(Modifier.padding(start = 48.dp, top = 92.dp).width(520.dp).alpha(1f - lowerFrac)) {
                    T("NUEVO EN TU LISTA · ${heroArt.kind}", EYEBROW, Color(0xFFC9C9C4))
                    Spacer(Modifier.height(6.dp))
                    TitleArt(heroArt.title, heroArt.logo, logoHeight = 84.dp)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (heroArt.meta.isNotBlank()) T(heroArt.meta, META, Color(0xFFD6D6D0))
                        heroArt.age?.let { Tag(it) }
                    }
                    if (heroArt.plot.isNotBlank()) T(heroArt.plot, SYNOPSIS.copy(fontSize = 13.sp, lineHeight = 19.sp), Color(0xFFE6E6E2), Modifier.padding(top = 8.dp), maxLines = 2)
                    Spacer(Modifier.height(16.dp))
                    val isFav = when (cur) { is VodStream -> app.favorites.contains("vod", cur.id); is SeriesItem -> app.favorites.contains("series", cur.id); else -> false }
                    val started = when (cur) {
                        is VodStream -> app.progress.entries.any { it.item.key == "vod:${cur.id}" && !it.done }
                        is SeriesItem -> app.progress.entries.any { it.item.kind == "episode" && it.item.seriesId == cur.id }
                        else -> false
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            lastKey = System.currentTimeMillis()
                            when {
                                // ← en el primer botón y → en el último pasan de novedad
                                e.key == Key.DirectionLeft && btnIdx == 0 && featured.size > 1 -> { featIdx = (featIdx - 1 + featured.size) % featured.size; true }
                                e.key == Key.DirectionRight && btnIdx == 2 && featured.size > 1 -> { featIdx = (featIdx + 1) % featured.size; true }
                                else -> false
                            }
                        },
                    ) {
                        Box(Modifier.onFocusChanged { if (it.hasFocus) btnIdx = 0 }) {
                            PrimaryAction(if (started) "Continuar" else "Reproducir", null, playBtn, canFocus = !lower) {
                                when (cur) {
                                    is VodStream -> play(ctx, listOf(PlayItem(kind = "vod", id = cur.id, ext = cur.ext, title = cur.name, icon = cur.icon)), 0)
                                    is SeriesItem -> scope.launch { playSeries(app, ctx, cur) }
                                }
                            }
                        }
                        PillAction("ⓘ  Más información", !lower, rememberReturnFocus(nav, "home:hero"), onFocused = { btnIdx = 1 }) {
                            when (cur) {
                                is VodStream -> nav.open(Route.Movie(cur), "home:hero")
                                is SeriesItem -> nav.open(Route.Show(cur), "home:hero")
                            }
                        }
                        PillAction(if (isFav) "★" else "＋", !lower, onFocused = { btnIdx = 2 }) {
                            when (cur) {
                                is VodStream -> app.favorites.toggle(cur.toFav())
                                is SeriesItem -> app.favorites.toggle(cur.toFav())
                            }
                        }
                    }
                }
                // Puntos de posición
                if (featured.size > 1) Row(
                    Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = screenH - 176.dp).alpha(1f - lowerFrac),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    featured.indices.forEach { i ->
                        Box(Modifier.size(if (i == featIdx) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (i == featIdx) Color.White else Color.White.copy(alpha = 0.4f)))
                    }
                }
            }

            // ---- Abajo: una sola fila, pegada al borde; al bajar, encima la información de lo seleccionado ----
            val rows = buildList { if (cont.isNotEmpty()) add("cw"); if (movies.isNotEmpty()) add("mv"); if (shows.isNotEmpty()) add("sr") }
            if (rows.isNotEmpty()) {
                val rowIdx = focusedRow.coerceIn(0, rows.lastIndex)
                val r = rows[rowIdx]
                var blockH by remember { mutableStateOf(240.dp) }
                // En la portada solo asoman el rótulo y el principio de la fila
                val y by animateDpAsState(if (lower) 0.dp else (blockH - 150.dp).coerceAtLeast(0.dp), tween(350), label = "fila")

                fun switchRow(to: Int) {
                    focusedRow = to
                    scope.launch {
                        delay(40)
                        try { rowFocus[rows[to]]?.requestFocus() } catch (_: Exception) { }
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = y)
                        .fillMaxWidth()
                        .onFocusChanged { if (it.hasFocus) lower = true }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { if (rowIdx < rows.lastIndex) switchRow(rowIdx + 1); true }
                                Key.DirectionUp -> { if (rowIdx > 0) switchRow(rowIdx - 1) else backToTop(); true }
                                else -> false
                            }
                        },
                ) {
                    val ra = rowArt
                    if (lower && ra != null) Column(Modifier.padding(start = 48.dp, bottom = 18.dp).width(600.dp).alpha(lowerFrac)) {
                        TitleArt(ra.title, ra.logo, logoHeight = 60.dp, style = BIG_TITLE.copy(fontSize = 30.sp, lineHeight = 34.sp))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ra.meta.isNotBlank()) T(ra.meta, META.copy(fontSize = 12.sp), Color(0xFFD6D6D0))
                            ra.age?.let { Tag(it) }
                        }
                        T(ra.plot.ifBlank { " " }, Type.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp), Color(0xFFE0E0DC), Modifier.padding(top = 6.dp), maxLines = 2, minLines = 2)
                    }
                    Column(Modifier.onSizeChanged { blockH = with(density) { it.height.toDp() } }) {
                        // Rótulo con pistas de las otras filas
                        fun name(k: String) = when (k) { "cw" -> "Seguir viendo"; "mv" -> "Películas nuevas"; else -> "Series nuevas" }
                        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(name(r), SECTION, Color.White, Modifier.weight(1f))
                            if (lower) {
                                val hints = listOfNotNull(
                                    rows.getOrNull(rowIdx - 1)?.let { "↑ " + name(it) },
                                    rows.getOrNull(rowIdx + 1)?.let { "↓ " + name(it) },
                                ).joinToString("   ")
                                T(hints, Type.small, MUTED)
                            }
                        }
                        when (r) {
                            "cw" -> ContinueRow(app, nav, cont, onFocusCard = { focusItem = it }, firstItemModifier = Modifier.focusRequester(rowFocus.getValue("cw")))
                            "mv" -> PosterRow(movies, nav, app, Modifier.focusRequester(rowFocus.getValue("mv"))) { focusItem = it }
                            else -> PosterRow(shows, nav, app, Modifier.focusRequester(rowFocus.getValue("sr"))) { focusItem = it }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}

/** Fila de carátulas (películas o series nuevas). */
@Composable
private fun PosterRow(items: List<Any>, nav: Nav, app: VisorApp, firstItemModifier: Modifier, onFocus: (Any) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 4.dp),
    ) {
        itemsIndexed(items, key = { _, it -> if (it is VodStream) "v${it.id}" else "s${(it as SeriesItem).id}" }) { idx, it ->
            val m = if (idx == 0) firstItemModifier else Modifier
            when (it) {
                is VodStream -> {
                    val k = "home:mv:${it.id}"
                    Poster(
                        url = it.icon, title = displayTitle(it.name), width = 108.dp, modifier = m,
                        onClick = { nav.open(Route.Movie(it), k) },
                        onMenu = { app.favorites.toggle(it.toFav()) },
                        onFocused = { onFocus(it) },
                        focusRequester = rememberReturnFocus(nav, k),
                    )
                }
                is SeriesItem -> {
                    val k = "home:sr:${it.id}"
                    Poster(
                        url = it.cover, title = displayTitle(it.name), width = 108.dp, modifier = m,
                        onClick = { nav.open(Route.Show(it), k) },
                        onMenu = { app.favorites.toggle(it.toFav()) },
                        onFocused = { onFocus(it) },
                        focusRequester = rememberReturnFocus(nav, k),
                    )
                }
            }
        }
    }
}

/** "Reproducir" de una serie en la portada: el episodio a medias, el siguiente al último visto o el primero. */
private suspend fun playSeries(app: VisorApp, ctx: android.content.Context, s: SeriesItem) {
    val si = safe { app.repo.seriesInfo(s.id) } ?: return
    val last = app.progress.entries.filter { it.item.kind == "episode" && it.item.seriesId == s.id }.maxByOrNull { it.updated }
    val all = si.seasons.toSortedMap().values.flatMap { eps -> eps.sortedBy { it.num } }
    val target = last?.let { l -> if (l.done) nextEpisode(si, l.item.id) else all.firstOrNull { it.id == l.item.id } } ?: all.firstOrNull() ?: return
    val queue = seasonQueue(s.id, s.name, s.cover, si, target.season)
    play(ctx, queue, queue.indexOfFirst { it.id == target.id }.coerceAtLeast(0))
}
