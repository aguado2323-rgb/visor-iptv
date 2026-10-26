package com.visor.tv.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.ArtSource
import com.visor.tv.data.Episode
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

// Las fichas son siempre oscuras, como en un cine: la imagen ocupa toda la pantalla y el texto va encima.
// Tienen dos estados: arriba la ficha completa; al bajar, la cabecera se encoge y manda el contenido de abajo
// (episodios en series; detalles y más títulos en películas). ↑ o Atrás vuelven a la ficha completa.

private val EYEBROW = Type.small.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
private val BIG_TITLE = Type.title.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
private val SMALL_TITLE = Type.title.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
private val META = Type.body.copy(fontSize = 13.sp)
private val SYNOPSIS = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
private val SECTION = Type.h2.copy(fontSize = 15.sp)
private val MUTED = Color(0xFF9A9DA3)

private fun hms(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** "00:52:10" → "52 min"; lo demás tal cual. */
private fun fmtDuration(s: String): String {
    val parts = s.trim().split(":").mapNotNull { it.toIntOrNull() }
    if (parts.size < 2) return s.trim()
    val min = if (parts.size == 3) parts[0] * 60 + parts[1] else parts[0]
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** Abre el tráiler de YouTube si hay una app que lo pueda abrir. */
private fun openTrailer(ctx: Context, code: String): Boolean {
    val url = if (code.startsWith("http")) code else "https://www.youtube.com/watch?v=$code"
    return try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

// ---------------- Piezas comunes ----------------

/**
 * Fondo a pantalla completa. Si solo hay carátula vertical (sin imagen apaisada), va a la derecha a toda
 * altura en vez de ampliarla y pixelarla. Al bajar se oscurece para que mande el contenido.
 */
@Composable
private fun CinemaBackdrop(backdrop: String?, poster: String, lower: Boolean) {
    val dim by animateFloatAsState(if (lower) 0.62f else 0f, tween(350), label = "oscurecer")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!backdrop.isNullOrBlank()) {
            Img(backdrop, Modifier.fillMaxSize())
        } else if (poster.isNotBlank()) {
            Img(poster, Modifier.fillMaxHeight().aspectRatio(2f / 3f).align(Alignment.CenterEnd).padding(end = 0.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.30f to Color.Black.copy(alpha = 0.92f), 0.52f to Color.Black.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.40f to Color.Transparent, 0.70f to Color.Black.copy(alpha = 0.75f), 1f to Color.Black)))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

/** Botón principal: blanco y grande al enfocarlo, con la barra de progreso dentro si está a medias. */
@Composable
private fun PrimaryAction(text: String, progress: Float?, focusRequester: FocusRequester, canFocus: Boolean, onClick: () -> Unit) {
    Focusable(
        onClick = onClick,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(50),
        scaleOnFocus = true,
        modifier = Modifier.focusProperties { this.canFocus = canFocus },
    ) { focused ->
        val fg = if (focused) Color.Black else Color.White
        Column(
            Modifier.background(if (focused) Color.White else Color.White.copy(alpha = 0.16f)).padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            T("▶  $text", Type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), fg)
            if (progress != null) {
                Box(Modifier.width(150.dp).height(3.dp).clip(CircleShape).background(fg.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(3.dp).background(fg))
                }
            }
        }
    }
}

/** Acción secundaria: icono redondo con su nombre debajo. */
@Composable
private fun RoundAction(icon: String, label: String, canFocus: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.width(76.dp)) {
        Focusable(
            onClick = onClick,
            shape = CircleShape,
            scaleOnFocus = true,
            modifier = Modifier.focusProperties { this.canFocus = canFocus },
        ) { focused ->
            Box(
                Modifier.size(42.dp).background(if (focused) Color.White else Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { T(icon, Type.label.copy(fontSize = 16.sp), if (focused) Color.Black else Color.White) }
        }
        T(label, Type.small.copy(fontSize = 10.sp), Color(0xFFCFCFCA))
    }
}

/** Etiqueta con borde: 4K, HDR, 5.1, edad… */
@Composable
private fun Tag(text: String) {
    Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T(text, Type.small.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), Color.White)
    }
}

/** Cabecera pequeña del estado "abajo": título y datos en una línea. */
@Composable
private fun CompactHeader(title: String, meta: String, alpha: Float, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 30.dp).alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        T(title, SMALL_TITLE, Color.White, Modifier.widthIn(max = 520.dp))
        T(meta, Type.small.copy(fontSize = 12.sp), Color(0xFFC9C9C4), Modifier.weight(1f))
        if (trailing != null) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp)) {
                T(trailing, Type.label.copy(fontWeight = FontWeight.Bold), Color.Black)
            }
        }
    }
}

/** Ayuda de teclas abajo en el estado "abajo". */
@Composable
private fun KeyHint(text: String, alpha: Float, modifier: Modifier) {
    T(text, Type.small, MUTED, modifier.padding(start = 48.dp, bottom = 18.dp).alpha(alpha))
}

// ---------------- Ficha de película ----------------

@Composable
fun MovieDetail(app: VisorApp, nav: Nav, vod: VodStream) = CompositionLocalProvider(LocalPalette provides OledPalette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = load(vod.id) { app.repo.vodInfo(vod.id) }
    val useTmdb = app.settings.artSource == ArtSource.TMDB && app.tmdb.available
    val tmdb = load(vod.id, useTmdb, info.orNull()?.tmdbId) {
        if (useTmdb) app.tmdb.art(info.orNull()?.tmdbId.orEmpty(), isTv = false) else null
    }.orNull()
    val i = info.orNull()
    val playBtn = remember { FocusRequester() }
    val isFav = app.favorites.contains("vod", vod.id)
    val resume = app.progress.entries.firstOrNull { it.item.key == "vod:${vod.id}" && !it.done }
    var trailerMsg by remember { mutableStateOf<String?>(null) }
    var lower by remember { mutableStateOf(false) }

    // Más títulos de la misma carpeta del proveedor (no de las ocultas o bloqueadas)
    val more = load(vod.id, app.repo.version) {
        val cat = vod.categoryId.ifBlank { app.repo.allVod().firstOrNull { it.id == vod.id }?.categoryId.orEmpty() }
        if (cat.isBlank() || app.folders.excludes("vod")(cat)) null
        else {
            val name = app.repo.vodCategories().firstOrNull { it.id == cat }?.name.orEmpty()
            val list = app.repo.vodStreams(cat).filter { it.id != vod.id }.sortedByDescending { it.added }.take(24)
            if (list.isEmpty()) null else name to list
        }
    }.orNull()

    val title = i?.name?.ifBlank { null } ?: vod.name
    val year = i?.year?.ifBlank { null } ?: vod.year.takeIf { it > 0 }?.toString()
    val duration = i?.duration?.ifBlank { null }?.let { fmtDuration(it) }
    val stars = tmdb?.rating?.ifBlank { null }?.let { "★ $it" } ?: rating(i?.rating ?: vod.rating)
    val meta = listOfNotNull(year, duration, i?.genre?.ifBlank { null }, stars).joinToString("  ·  ")
    val plot = tmdb?.overview?.ifBlank { null } ?: i?.plot.orEmpty()
    val item = PlayItem(kind = "vod", id = vod.id, ext = i?.ext ?: vod.ext, title = vod.name, icon = vod.icon)

    fun backToTop() {
        lower = false
        scope.launch { delay(60); try { playBtn.requestFocus() } catch (_: Exception) { } }
    }
    BackHandler(enabled = lower) { backToTop() }

    val headerAlpha by animateFloatAsState(if (lower) 0f else 1f, tween(300), label = "cabecera")
    val lowerAlpha = 1f - headerAlpha

    Box(Modifier.fillMaxSize()) {
        CinemaBackdrop(
            tmdb?.backdrop?.ifBlank { null } ?: i?.backdrop?.ifBlank { null },
            tmdb?.poster?.ifBlank { null } ?: i?.cover?.ifBlank { null } ?: vod.icon,
            lower,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenH = maxHeight

            // ---- Ficha completa ----
            Column(Modifier.padding(start = 48.dp, top = 40.dp).width(500.dp).alpha(headerAlpha)) {
                T("PELÍCULA", EYEBROW, Color(0xFFC9C9C4))
                Spacer(Modifier.height(4.dp))
                T(title, BIG_TITLE, Color.White, maxLines = 2)
                if (meta.isNotBlank()) T(meta, META, Color(0xFFD6D6D0), Modifier.padding(top = 6.dp))
                val tags = listOfNotNull(i?.video?.ifBlank { null }, i?.audio?.ifBlank { null }, i?.age?.ifBlank { null })
                if (tags.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Tag(it) } }
                Spacer(Modifier.height(10.dp))
                when {
                    plot.isNotBlank() -> T(plot, SYNOPSIS, Color(0xFFE6E6E2), maxLines = 3)
                    info is Load.Loading -> T("Cargando sinopsis…", SYNOPSIS, MUTED)
                    else -> T("El proveedor no incluye sinopsis para esta película.", SYNOPSIS, MUTED)
                }
                val credits = listOfNotNull(
                    i?.director?.ifBlank { null }?.let { "Dirección: $it" },
                    i?.cast?.ifBlank { null }?.let { "Con $it" },
                ).joinToString(" · ")
                if (credits.isNotBlank()) T(credits, Type.small, MUTED, Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryAction(
                        if (resume != null) "Continuar" else "Reproducir",
                        resume?.let { it.positionMs.toFloat() / it.durationMs },
                        playBtn, canFocus = !lower,
                    ) { play(ctx, listOf(item), 0) }
                    Spacer(Modifier.width(8.dp))
                    if (resume != null) RoundAction("↺", "Desde el inicio", !lower) {
                        app.progress.update(item, 0, 1); play(ctx, listOf(item), 0)
                    }
                    val trailer = i?.trailer.orEmpty()
                    if (trailer.isNotBlank()) RoundAction("▷", "Tráiler", !lower) {
                        if (!openTrailer(ctx, trailer)) trailerMsg = "No hay ninguna app para abrir el tráiler (instala YouTube)."
                    }
                    RoundAction(if (isFav) "★" else "☆", if (isFav) "En mi lista" else "Mi lista", !lower) { app.favorites.toggle(vod.toFav()) }
                }
                val hint = trailerMsg ?: resume?.let { "Quedan ${hms(it.durationMs - it.positionMs)}" }
                if (hint != null) T(hint, Type.small, MUTED, Modifier.padding(top = 8.dp))
            }

            // ---- Estado "abajo": cabecera pequeña y detalles ----
            CompactHeader(title, meta, lowerAlpha, trailing = if (resume != null) "▶ Continuar" else "▶ Reproducir")
            Row(
                Modifier.padding(start = 48.dp, end = 48.dp, top = 84.dp).fillMaxWidth().alpha(lowerAlpha),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(Modifier.weight(1.2f)) {
                    T("Sobre la película", SECTION, Color.White)
                    Spacer(Modifier.height(8.dp))
                    T(plot.ifBlank { "Sin sinopsis." }, Type.body.copy(fontSize = 12.sp, lineHeight = 18.sp), Color(0xFFD6D6D0), maxLines = 8)
                }
                Column(Modifier.weight(1f)) {
                    T("Ficha", SECTION, Color.White)
                    Spacer(Modifier.height(8.dp))
                    val rows = listOfNotNull(
                        i?.director?.ifBlank { null }?.let { "DIRECCIÓN" to it },
                        i?.cast?.ifBlank { null }?.let { "REPARTO" to it },
                        i?.originalName?.ifBlank { null }?.takeIf { !it.equals(title, true) }?.let { "TÍTULO ORIGINAL" to it },
                        listOfNotNull(i?.country?.ifBlank { null }, i?.releaseDate?.ifBlank { null }).joinToString(" · ").ifBlank { null }?.let { "PAÍS · ESTRENO" to it },
                        listOfNotNull(i?.video?.ifBlank { null }, i?.audio?.ifBlank { null }).joinToString(" · ").ifBlank { null }?.let { "IMAGEN Y SONIDO" to it },
                    )
                    if (rows.isEmpty()) T("El proveedor no da más datos de esta película.", Type.small, MUTED)
                    rows.forEach { (k, v) ->
                        Row(Modifier.padding(bottom = 6.dp)) {
                            T(k, Type.small.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp), MUTED, Modifier.width(118.dp))
                            T(v, Type.small.copy(fontSize = 12.sp, lineHeight = 17.sp), Color.White, maxLines = 2)
                        }
                    }
                }
            }

            // ---- Más de la misma carpeta: asoma abajo y sube al bajar ----
            more?.let { (catName, list) ->
                val y by animateDpAsState(if (lower) 278.dp else screenH - 104.dp, tween(350), label = "mas")
                Column(
                    Modifier
                        .offset(y = y)
                        .fillMaxWidth()
                        .onFocusChanged { lower = it.hasFocus }
                        .focusGroup()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { backToTop(); true } else false
                        },
                ) {
                    Row(Modifier.padding(start = 48.dp, end = 48.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T(if (catName.isNotBlank()) "Más de «$catName»" else "Más películas", SECTION, Color.White, Modifier.weight(1f))
                        if (!lower) T("↓ Detalles y más", Type.small, MUTED)
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 8.dp),
                    ) {
                        itemsIndexed(list, key = { _, v -> v.id }) { _, v ->
                            val k = "more:${vod.id}:${v.id}"
                            Poster(
                                url = v.icon,
                                title = v.name,
                                subtitle = listOfNotNull(v.year.takeIf { it > 0 }?.toString(), rating(v.rating)).joinToString("  ").ifBlank { null },
                                onClick = { nav.open(Route.Movie(v), k) },
                                onMenu = { app.favorites.toggle(v.toFav()) },
                                focusRequester = rememberReturnFocus(nav, k),
                            )
                        }
                    }
                }
                KeyHint("← → recorrer · ↑ volver a la ficha · OK abrir", lowerAlpha, Modifier.align(Alignment.BottomStart))
            }
        }
    }
    LaunchedEffect(Unit) { if (nav.returnKey == null) try { playBtn.requestFocus() } catch (_: Exception) { } }
}

// ---------------- Ficha de serie ----------------

@Composable
fun SeriesDetail(app: VisorApp, series: SeriesItem) = CompositionLocalProvider(LocalPalette provides OledPalette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = load(series.id) { app.repo.seriesInfo(series.id) }
    val useTmdb = app.settings.artSource == ArtSource.TMDB && app.tmdb.available
    val tmdb = load(series.id, useTmdb, info.orNull()?.tmdbId) {
        if (useTmdb) app.tmdb.art(info.orNull()?.tmdbId.orEmpty(), isTv = true) else null
    }.orNull()
    val si = info.orNull()
    val isFav = app.favorites.contains("series", series.id)
    val mainBtn = remember { FocusRequester() }
    var trailerMsg by remember { mutableStateOf<String?>(null) }
    var lower by remember { mutableStateOf(false) }

    // Último episodio de esta serie: a medias → "Continuar"; terminado → "Siguiente"
    val lastWatched = app.progress.entries.filter { it.item.kind == "episode" && it.item.seriesId == series.id }.maxByOrNull { it.updated }
    val allEpisodes = si?.seasons?.values?.flatten().orEmpty()
    val resumeEp = lastWatched?.let { lw ->
        if (lw.done) si?.let { nextEpisode(it, lw.item.id) } else allEpisodes.firstOrNull { it.id == lw.item.id }
    }
    val resumeEntry = lastWatched?.takeIf { !it.done && resumeEp != null }
    val firstEp = si?.seasons?.toSortedMap()?.values?.firstOrNull()?.minByOrNull { it.num }

    var season by remember { mutableIntStateOf(-1) }
    LaunchedEffect(si) {
        if (si != null && season !in si.seasons.keys) season = resumeEp?.season ?: si.seasons.keys.minOrNull() ?: -1
    }

    fun playEpisode(ep: Episode) {
        val s = si ?: return
        val queue = seasonQueue(series.id, series.name, series.cover, s, ep.season)
        play(ctx, queue, queue.indexOfFirst { it.id == ep.id }.coerceAtLeast(0))
    }
    fun backToTop() {
        lower = false
        scope.launch { delay(60); try { mainBtn.requestFocus() } catch (_: Exception) { } }
    }
    BackHandler(enabled = lower) { backToTop() }

    val title = si?.name?.ifBlank { null } ?: series.name
    val nSeasons = si?.seasons?.size ?: 0
    val stars = tmdb?.rating?.ifBlank { null }?.let { "★ $it" } ?: rating(si?.rating ?: series.rating)
    val meta = listOfNotNull(
        si?.year?.ifBlank { null } ?: series.year.takeIf { it > 0 }?.toString(),
        si?.genre?.ifBlank { null },
        if (nSeasons > 0) "$nSeasons ${if (nSeasons == 1) "temporada" else "temporadas"}" else null,
        stars,
    ).joinToString("  ·  ")
    val plot = tmdb?.overview?.ifBlank { null } ?: si?.plot?.ifBlank { null } ?: series.plot

    val headerAlpha by animateFloatAsState(if (lower) 0f else 1f, tween(300), label = "cabecera")
    val lowerAlpha = 1f - headerAlpha

    Box(Modifier.fillMaxSize()) {
        CinemaBackdrop(
            tmdb?.backdrop?.ifBlank { null } ?: si?.backdrop?.ifBlank { null },
            tmdb?.poster?.ifBlank { null } ?: si?.cover?.ifBlank { null } ?: series.cover,
            lower,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenH = maxHeight

            // ---- Ficha completa ----
            Column(Modifier.padding(start = 48.dp, top = 40.dp).width(500.dp).alpha(headerAlpha)) {
                T("SERIE", EYEBROW, Color(0xFFC9C9C4))
                Spacer(Modifier.height(4.dp))
                T(title, BIG_TITLE, Color.White, maxLines = 2)
                if (meta.isNotBlank()) T(meta, META, Color(0xFFD6D6D0), Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                T(
                    plot.ifBlank { if (info is Load.Loading) "Cargando…" else "Sin descripción del proveedor." },
                    SYNOPSIS, Color(0xFFE6E6E2), maxLines = 3,
                )
                val credits = listOfNotNull(
                    si?.cast?.ifBlank { null }?.let { "Con $it" },
                    si?.director?.ifBlank { null }?.let { "Creada por $it" },
                ).joinToString(" · ")
                if (credits.isNotBlank()) T(credits, Type.small, MUTED, Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val target = resumeEp ?: firstEp
                    val label = when {
                        resumeEp != null && lastWatched?.done == true -> "Siguiente T${resumeEp.season} · E${resumeEp.num}"
                        resumeEp != null -> "Continuar T${resumeEp.season} · E${resumeEp.num}"
                        firstEp != null -> "Empezar T${firstEp.season} · E${firstEp.num}"
                        info is Load.Loading -> "Cargando episodios…"
                        else -> "Sin episodios"
                    }
                    PrimaryAction(
                        label, resumeEntry?.let { it.positionMs.toFloat() / it.durationMs },
                        mainBtn, canFocus = !lower,
                    ) { target?.let { playEpisode(it) } }
                    Spacer(Modifier.width(8.dp))
                    if (resumeEntry != null && resumeEp != null) RoundAction("↺", "Desde el inicio", !lower) {
                        app.progress.update(resumeEntry.item, 0, 1); playEpisode(resumeEp)
                    }
                    val trailer = si?.trailer.orEmpty()
                    if (trailer.isNotBlank()) RoundAction("▷", "Tráiler", !lower) {
                        if (!openTrailer(ctx, trailer)) trailerMsg = "No hay ninguna app para abrir el tráiler (instala YouTube)."
                    }
                    RoundAction(if (isFav) "★" else "☆", if (isFav) "En mi lista" else "Mi lista", !lower) { app.favorites.toggle(series.toFav()) }
                }
                val hint = trailerMsg ?: if (resumeEp != null && resumeEntry != null) {
                    "T${resumeEp.season} · E${resumeEp.num} «${resumeEp.title}» · quedan ${hms(resumeEntry.durationMs - resumeEntry.positionMs)}"
                } else null
                if (hint != null) T(hint, Type.small, MUTED, Modifier.padding(top = 8.dp))
            }

            // ---- Estado "abajo": cabecera pequeña ----
            CompactHeader(title, meta, lowerAlpha)

            // ---- Temporadas y episodios: asoman abajo y suben al bajar ----
            if (si != null && si.seasons.isNotEmpty()) {
                val y by animateDpAsState(if (lower) 80.dp else screenH - 112.dp, tween(350), label = "episodios")
                val seasons = si.seasons.keys.sorted()
                Column(Modifier.offset(y = y).fillMaxWidth().onFocusChanged { lower = it.hasFocus }.focusGroup()) {
                    Row(
                        Modifier
                            .padding(start = 42.dp, end = 48.dp)
                            .fillMaxWidth()
                            // ↑ desde las temporadas vuelve a la ficha completa
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { backToTop(); true } else false
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(seasons) { _, s ->
                                SeasonTab("Temporada $s", s == season) { season = s }
                            }
                        }
                        val n = si.seasons[season]?.size ?: 0
                        T(if (lower) "$n episodios" else "↓ Episodios", Type.small.copy(fontSize = 12.sp), MUTED)
                    }
                    val eps = si.seasons[season].orEmpty().sortedBy { it.num }
                    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = eps.indexOfFirst { it.id == resumeEp?.id }.coerceAtLeast(0))
                    LaunchedEffect(season) {
                        val idx = eps.indexOfFirst { it.id == resumeEp?.id }
                        rowState.scrollToItem(if (idx >= 0) idx else 0)
                    }
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 8.dp),
                    ) {
                        itemsIndexed(eps, key = { _, e -> e.id }) { _, e ->
                            EpisodeCard(app, series, e, if (lastWatched?.done == true && lastWatched.item.id == e.id) "Visto" else null) { playEpisode(e) }
                        }
                    }
                }
                KeyHint("← → episodios · ↑ temporadas y ficha · OK reproducir", lowerAlpha, Modifier.align(Alignment.BottomStart))
            }
        }
    }
    LaunchedEffect(si != null) { if (si != null) try { mainBtn.requestFocus() } catch (_: Exception) { } }
}

/** Pestaña de temporada: texto con subrayado; al enfocarla se cambia de temporada. */
@Composable
private fun SeasonTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    Focusable(onClick = onSelect, onFocused = onSelect, shape = RoundedCornerShape(10.dp)) { focused ->
        Column(
            Modifier.background(if (focused) Color.White else Color.Transparent).padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            T(label, Type.tab, if (focused) Color.Black else if (selected) Color.White else MUTED)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(28.dp).height(2.dp).clip(CircleShape).background(if (selected && !focused) Color.White else Color.Transparent))
        }
    }
}

/** Tarjeta de episodio: imagen grande, número, progreso o visto, y su sinopsis siempre en el mismo hueco. */
@Composable
private fun EpisodeCard(app: VisorApp, series: SeriesItem, e: Episode, badge: String?, onClick: () -> Unit) {
    val prog = app.progress.entries.firstOrNull { it.item.key == "episode:${e.id}" && !it.done }
    val cardW: Dp = 252.dp
    Column(Modifier.width(cardW)) {
        Focusable(onClick = onClick, shape = RoundedCornerShape(10.dp), scaleOnFocus = true) { focused ->
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            ) {
                Img(e.image.ifBlank { series.cover }, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                Box(Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    T("${e.num}", Type.small.copy(fontWeight = FontWeight.Bold), Color.White)
                }
                if (badge != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        T("✓", Type.small.copy(fontWeight = FontWeight.Bold), Color.White)
                    }
                }
                if (prog != null) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 9.dp, vertical = 8.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth((prog.positionMs.toFloat() / prog.durationMs).coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        T(e.title.ifBlank { "Episodio ${e.num}" }, Type.label.copy(fontSize = 14.sp), Color.White)
        val sub = listOfNotNull(
            e.duration.ifBlank { null }?.let { fmtDuration(it) },
            prog?.let { "Quedan ${hms(it.durationMs - it.positionMs)}" } ?: badge,
        ).joinToString(" · ")
        T(sub.ifBlank { " " }, Type.small.copy(fontSize = 11.sp), MUTED, Modifier.padding(top = 2.dp))
        // Siempre tres líneas reservadas: la fila no salta al recorrerla
        T(e.plot.ifBlank { " " }, Type.small.copy(fontSize = 11.sp, lineHeight = 15.sp), Color(0xFFC4C6CA), Modifier.padding(top = 4.dp), maxLines = 3, minLines = 3)
    }
}
