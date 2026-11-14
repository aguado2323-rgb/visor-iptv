package com.visor.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.addons.AddonStream
import com.visor.tv.addons.CatalogRow
import com.visor.tv.addons.InstalledAddon
import com.visor.tv.addons.MetaDetail
import com.visor.tv.addons.MetaPreview
import com.visor.tv.addons.MetaVideo
import com.visor.tv.addons.StreamInfo
import com.visor.tv.data.FavItem
import com.visor.tv.data.PlayItem
import com.visor.tv.data.next
import com.visor.tv.debrid.DebridKind
import com.visor.tv.debrid.DeviceCode
import com.visor.tv.play
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.Img
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.OledPalette
import com.visor.tv.ui.Poster
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Ficha de un título de Descubrir. Es la misma ficha de cine que la de las películas y series del proveedor
// (mismas piezas), pero los datos salen del addon y de TMDB, y "Ver" pasa antes por elegir una fuente.

private fun MetaVideo.label(): String = "T$season · E$episode"

/** Elemento reproducible de un episodio (o de la película, si [video] es null). */
private fun playItem(m: MetaPreview, title: String, poster: String, video: MetaVideo?, epTitle: String = ""): PlayItem =
    if (video == null) PlayItem(kind = "addon", id = 0, title = title, icon = poster, ref = m.key, parent = m.key)
    else PlayItem(
        kind = "addon", id = 0,
        title = listOf(video.label(), epTitle.ifBlank { video.title }).filter { it.isNotBlank() }.joinToString("  "),
        subtitle = title, icon = poster,
        ref = "${m.type}/${video.id}", parent = m.key, season = video.season, episode = video.episode,
    )

@Composable
fun AddonDetail(app: VisorApp, nav: Nav, m: MetaPreview) = CompositionLocalProvider(LocalPalette provides OledPalette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = app.discover
    val info = load(m.key, d.version) { d.meta(m.type, m.id) }
    val meta = info.orNull()
    val base = meta?.preview ?: m
    // TMDB pone la ficha en español y añade logotipo, reparto, edad y los episodios con imagen
    val imdb = meta?.imdbId?.ifBlank { null } ?: m.id.substringBefore(':').takeIf { it.startsWith("tt") }
    val tm = load(m.key, imdb) {
        imdb?.let { app.tmdb.idByImdb(it, m.isSeries) }?.let { id -> if (m.isSeries) app.tmdb.tv(id, m.name, 0) else app.tmdb.movie(id, m.name, 0) }
    }.orNull()

    val title = tm?.title?.ifBlank { null } ?: base.name
    val poster = base.poster.ifBlank { tm?.poster.orEmpty() }
    val plot = tm?.overview?.ifBlank { null } ?: base.description
    val age = tm?.certification?.ifBlank { null }
    val cast = tm?.cast?.ifBlank { null } ?: meta?.cast?.ifBlank { null }
    val director = tm?.director?.ifBlank { null } ?: meta?.director?.ifBlank { null }
    val seasons = meta?.seasons.orEmpty()
    val nSeasons = seasons.keys.count { it > 0 }
    val metaLine = listOfNotNull(
        base.year.ifBlank { null },
        if (m.isSeries) nSeasons.takeIf { it > 0 }?.let { "$it ${if (it == 1) "temporada" else "temporadas"}" }
        else minutes(tm?.runtimeMin ?: 0) ?: meta?.runtime?.ifBlank { null },
        tm?.genres?.ifBlank { null } ?: base.genres.ifBlank { null },
        (base.rating.ifBlank { null } ?: tm?.rating?.ifBlank { null })?.let { "★ $it" },
    ).joinToString("  ·  ")

    val isFav = app.favorites.containsRef(m.key)
    val mainBtn = remember { FocusRequester() }
    var msg by remember { mutableStateOf<String?>(null) }
    var lower by remember { mutableStateOf(false) }
    /** Cola y posición para las que se está eligiendo fuente a mano. */
    var picking by remember { mutableStateOf<Pair<List<PlayItem>, Int>?>(null) }

    // ---- Qué toca ver ----
    val movieItem = playItem(m, title, poster, null)
    val mine = app.progress.entries.filter { it.item.kind == "addon" && it.item.parent == m.key }
    val last = mine.maxByOrNull { it.updated }
    val ordered = meta?.ordered.orEmpty()
    val resumeEp: MetaVideo? = if (!m.isSeries) null else last?.let { lw ->
        val i = ordered.indexOfFirst { "${m.type}/${it.id}" == lw.item.ref }
        if (i < 0) null else if (lw.done) ordered.getOrNull(i + 1) else ordered[i]
    }
    val resumeEntry = last?.takeIf { !it.done && (!m.isSeries || resumeEp != null) }
    val firstEp = ordered.firstOrNull { it.season > 0 } ?: ordered.firstOrNull()

    var season by remember { mutableIntStateOf(-1) }
    LaunchedEffect(meta) {
        if (meta != null && season !in seasons.keys) season = resumeEp?.season ?: seasons.keys.firstOrNull() ?: -1
    }
    val tmEps = load(tm?.id, season) { if (m.isSeries && season >= 0) tm?.let { app.tmdb.season(it.id, season) }.orEmpty() else emptyMap() }.orNull().orEmpty()

    fun queueFor(ep: MetaVideo): Pair<List<PlayItem>, Int> {
        val eps = seasons[ep.season].orEmpty()
        val q = eps.map { playItem(m, title, poster, it, if (it.season == season) tmEps[it.episode]?.name.orEmpty() else "") }
        return q to eps.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
    }

    /** OK: elige sola la mejor fuente (o pregunta, según Ajustes). [ask]: siempre pregunta. */
    fun watch(queue: List<PlayItem>, index: Int, ask: Boolean = false) {
        when {
            queue.isEmpty() -> Unit
            !d.hasStreamAddon -> msg = "Para reproducir hace falta un addon de fuentes. Añádelo en Descubrir → Addons y debrid."
            ask || !app.settings.autoSource -> picking = queue to index
            else -> play(ctx, queue, index)
        }
    }
    fun watchEpisode(ep: MetaVideo, ask: Boolean = false) = queueFor(ep).let { (q, i) -> watch(q, i, ask) }
    fun watchMain(ask: Boolean = false) {
        if (!m.isSeries) watch(listOf(movieItem), 0, ask) else (resumeEp ?: firstEp)?.let { watchEpisode(it, ask) }
    }

    fun backToTop() {
        lower = false
        scope.launch { delay(60); try { mainBtn.requestFocus() } catch (_: Exception) { } }
    }
    BackHandler(enabled = lower) { backToTop() }

    val headerAlpha by animateFloatAsState(if (lower) 0f else 1f, tween(300), label = "cabecera")
    val lowerAlpha = 1f - headerAlpha

    Box(Modifier.fillMaxSize()) {
        CinemaBackdrop(tm?.backdrop?.ifBlank { null } ?: base.backdropUrl().ifBlank { null }, poster, lower)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenH = maxHeight

            // ---- Ficha completa ----
            Column(Modifier.padding(start = 48.dp, top = 40.dp).width(500.dp).alpha(headerAlpha)) {
                T(if (m.isSeries) "SERIE" else "PELÍCULA", EYEBROW, Color(0xFFC9C9C4))
                Spacer(Modifier.height(4.dp))
                TitleArt(title, tm?.logo?.ifBlank { null } ?: base.logoUrl())
                if (metaLine.isNotBlank()) T(metaLine, META, Color(0xFFD6D6D0), Modifier.padding(top = 6.dp))
                if (age != null) Row(Modifier.padding(top = 8.dp)) { Tag(age) }
                Spacer(Modifier.height(10.dp))
                T(
                    plot.ifBlank { if (info is Load.Loading) "Cargando…" else "Sin sinopsis." },
                    SYNOPSIS, if (plot.isBlank()) MUTED else Color(0xFFE6E6E2), maxLines = 3,
                )
                val credits = listOfNotNull(
                    director?.let { if (m.isSeries) "Creada por $it" else "Dirección: $it" },
                    cast?.let { "Con $it" },
                ).joinToString(" · ")
                if (credits.isNotBlank()) T(credits, Type.small, MUTED, Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val label = when {
                        !m.isSeries -> if (resumeEntry != null) "Continuar" else "Ver"
                        resumeEp != null && last?.done == true -> "Siguiente ${resumeEp.label()}"
                        resumeEp != null -> "Continuar ${resumeEp.label()}"
                        firstEp != null -> "Empezar ${firstEp.label()}"
                        info is Load.Loading -> "Cargando episodios…"
                        else -> "Sin episodios"
                    }
                    PrimaryAction(label, resumeEntry?.let { it.positionMs.toFloat() / it.durationMs }, mainBtn, canFocus = !lower) { watchMain() }
                    Spacer(Modifier.width(8.dp))
                    RoundAction("☰", "Fuentes", !lower) { watchMain(ask = true) }
                    if (resumeEntry != null) RoundAction("↺", "Desde el inicio", !lower) {
                        app.progress.update(resumeEntry.item, 0, 1); watchMain()
                    }
                    val trailer = meta?.trailer.orEmpty()
                    if (trailer.isNotBlank()) RoundAction("▷", "Tráiler", !lower) {
                        if (!openTrailer(ctx, trailer)) msg = "No hay ninguna app para abrir el tráiler (instala YouTube)."
                    }
                    RoundAction(if (isFav) "★" else "☆", if (isFav) "En mi lista" else "Mi lista", !lower) {
                        app.favorites.toggle(base.copy(name = title, poster = poster).toFav())
                    }
                }
                val hint = msg ?: resumeEntry?.let {
                    listOfNotNull(resumeEp?.label(), "quedan ${hms(it.durationMs - it.positionMs)}").joinToString(" · ")
                } ?: if (!d.hasStreamAddon) "Aún no hay addon de fuentes: añádelo en Descubrir → Addons y debrid." else null
                if (hint != null) T(hint, Type.small, MUTED, Modifier.padding(top = 8.dp), maxLines = 2)
            }

            // ---- Estado "abajo": cabecera pequeña ----
            CompactHeader(title, metaLine, lowerAlpha)

            // ---- Temporadas y episodios: asoman abajo y suben al bajar ----
            if (m.isSeries && seasons.isNotEmpty()) {
                val y by animateDpAsState(if (lower) 80.dp else screenH - 112.dp, tween(350), label = "episodios")
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
                            items(seasons.keys.toList()) { s ->
                                SeasonTab(if (s == 0) "Especiales" else "Temporada $s", s == season) { season = s }
                            }
                        }
                        val n = seasons[season]?.size ?: 0
                        T(if (lower) "$n episodios" else "↓ Episodios", Type.small.copy(fontSize = 12.sp), MUTED)
                    }
                    val eps = seasons[season].orEmpty()
                    val rowState = rememberLazyListState()
                    LaunchedEffect(season, meta) {
                        val idx = eps.indexOfFirst { it.id == resumeEp?.id }
                        rowState.scrollToItem(if (idx >= 0) idx else 0)
                    }
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 8.dp),
                    ) {
                        items(eps, key = { it.id }) { e ->
                            val entry = mine.firstOrNull { it.item.ref == "${m.type}/${e.id}" }
                            val tmEp = tmEps[e.episode]
                            EpisodeTile(
                                image = tmEp?.still?.ifBlank { null } ?: e.thumbnail.ifBlank { poster },
                                number = e.episode,
                                name = tmEp?.name?.ifBlank { null } ?: e.title.ifBlank { "Episodio ${e.episode}" },
                                synopsis = tmEp?.overview?.ifBlank { null } ?: e.overview,
                                duration = minutes(tmEp?.runtimeMin ?: 0),
                                frac = entry?.takeIf { !it.done }?.let { it.positionMs.toFloat() / it.durationMs },
                                left = entry?.takeIf { !it.done }?.let { "Quedan ${hms(it.durationMs - it.positionMs)}" },
                                watched = entry?.done == true,
                                onClick = { watchEpisode(e) },
                                onMenu = { watchEpisode(e, ask = true) },
                            )
                        }
                    }
                }
                KeyHint("← → episodios · ↑ temporadas y ficha · OK reproducir · ≡ elegir fuente", lowerAlpha, Modifier.align(Alignment.BottomStart))
            }
        }
    }

    picking?.let { (queue, index) ->
        StreamPicker(app, queue[index], onDismiss = { picking = null }) { s ->
            d.choose(queue[index], s)
            picking = null
            play(ctx, queue, index)
        }
    }
    LaunchedEffect(Unit) { if (nav.returnKey == null) try { mainBtn.requestFocus() } catch (_: Exception) { } }
}

/** Tarjeta de episodio: imagen grande, número, progreso o visto, y su sinopsis siempre en el mismo hueco. */
@Composable
private fun EpisodeTile(
    image: String, number: Int, name: String, synopsis: String, duration: String?,
    frac: Float?, left: String?, watched: Boolean, onClick: () -> Unit, onMenu: () -> Unit,
) {
    Column(Modifier.width(252.dp)) {
        Focusable(onClick = onClick, onMenu = onMenu, shape = RoundedCornerShape(10.dp), scaleOnFocus = true) { focused ->
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            ) {
                Img(image, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                Box(Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    T("$number", Type.small.copy(fontWeight = FontWeight.Bold), Color.White)
                }
                if (watched) {
                    Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        T("✓", Type.small.copy(fontWeight = FontWeight.Bold), Color.White)
                    }
                }
                if (frac != null) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 9.dp, vertical = 8.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        T(name, Type.label.copy(fontSize = 14.sp), Color.White)
        val sub = listOfNotNull(duration, left ?: if (watched) "Visto" else null).joinToString(" · ")
        T(sub.ifBlank { " " }, Type.small.copy(fontSize = 11.sp), MUTED, Modifier.padding(top = 2.dp))
        // Siempre tres líneas reservadas: la fila no salta al recorrerla
        T(synopsis.ifBlank { " " }, Type.small.copy(fontSize = 11.sp, lineHeight = 15.sp), Color(0xFFC4C6CA), Modifier.padding(top = 4.dp), maxLines = 3, minLines = 3)
    }
}

// ---------------- Elegir fuente ----------------

/**
 * Lista de fuentes de un título, de mejor a peor según tus preferencias. Cada fila dice de un vistazo
 * calidad, imagen y sonido, idioma, tamaño y si empieza al instante.
 */
@Composable
fun StreamPicker(app: VisorApp, item: PlayItem, onDismiss: () -> Unit, onPick: (AddonStream) -> Unit) {
    val p = LocalPalette.current
    val d = app.discover
    val state = load(item.key) { d.streams(item) }
    val first = remember { FocusRequester() }
    var note by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(780.dp).heightIn(max = 480.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Fuentes", Type.h2)
                    T(listOf(item.subtitle, item.title).filter { it.isNotBlank() }.joinToString(" · "), Type.small, p.muted)
                }
                state.orNull()?.let { r -> T("${r.streams.size} ${if (r.streams.size == 1) "fuente" else "fuentes"}", Type.small, p.muted) }
            }
            Spacer(Modifier.height(10.dp))
            when (state) {
                Load.Loading -> T("Preguntando a tus addons…", Type.body, p.muted, Modifier.padding(6.dp))
                is Load.Err -> T("No se pudieron cargar las fuentes: ${state.msg}", Type.body, p.muted, Modifier.padding(6.dp), maxLines = 3)
                is Load.Ok -> {
                    val r = state.value
                    if (r.streams.isEmpty()) {
                        T(
                            if (r.failed.isNotEmpty()) "Los addons de fuentes no responden (${r.failed.joinToString()}). Prueba dentro de un momento."
                            else "Ningún addon tiene fuentes para este título.",
                            Type.body, p.muted, Modifier.padding(6.dp), maxLines = 3,
                        )
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            itemsIndexed(r.streams, key = { _, s -> s.stream.id }) { i, s ->
                                val ok = d.playable(s.stream)
                                StreamRow(s, ok, if (i == 0) first else null) {
                                    if (ok) onPick(s.stream) else note = "Esa fuente necesita una cuenta debrid. Conéctala en Descubrir → Addons y debrid."
                                }
                            }
                        }
                        LaunchedEffect(r) { try { first.requestFocus() } catch (_: Exception) { } }
                    }
                    val foot = note ?: r.failed.takeIf { it.isNotEmpty() && r.streams.isNotEmpty() }?.let { "Sin respuesta de: ${it.joinToString()}" }
                    if (foot != null) T(foot, Type.small, p.muted, Modifier.padding(start = 6.dp, top = 8.dp), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun StreamRow(s: StreamInfo, playable: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Focusable(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { focused ->
        val fg = (if (focused) p.focusText else p.text).copy(alpha = if (playable) 1f else 0.45f)
        val dim = fg.copy(alpha = fg.alpha * 0.68f)
        Row(
            Modifier.fillMaxWidth().background(if (focused) p.focusBg else Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Calidad, siempre en el mismo sitio para comparar de un vistazo
            Box(Modifier.width(58.dp).border(1.dp, fg.copy(alpha = fg.alpha * 0.5f), RoundedCornerShape(6.dp)).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                T(s.quality.ifBlank { "—" }, Type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), fg)
            }
            Column(Modifier.weight(1f)) {
                val facts = listOfNotNull(
                    s.hdr.ifBlank { null }, s.codec.ifBlank { null }, s.audio.ifBlank { null },
                    when { s.spanish -> "Español"; s.multi -> "Multi"; else -> null },
                    s.size.ifBlank { null },
                ).joinToString("  ·  ")
                T(facts.ifBlank { s.stream.name.replace('\n', ' ').ifBlank { "Fuente" } }, Type.label, fg)
                val file = s.stream.filename.ifBlank { s.stream.description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
                if (file.isNotBlank()) T(file, Type.small, dim)
            }
            Column(horizontalAlignment = Alignment.End) {
                T(
                    when {
                        !playable -> "Necesita debrid"
                        s.cached == true -> "⚡ Al instante"
                        s.cached == false -> "No está en caché"
                        else -> ""
                    },
                    Type.small.copy(fontWeight = FontWeight.SemiBold), fg,
                )
                T(s.stream.addon, Type.small, dim)
            }
        }
    }
}
