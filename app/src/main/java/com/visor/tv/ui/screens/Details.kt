package com.visor.tv.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type

// ---------------- Piezas comunes de las fichas ----------------

/** Imagen a todo el alto con degradados para que el texto se lea. */
@Composable
private fun DetailBackdrop(url: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Img(url, Modifier.fillMaxHeight().fillMaxWidth(0.72f).align(Alignment.CenterEnd))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.28f to p.bg, 0.55f to p.bg.copy(alpha = 0.78f), 0.85f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to p.bg.copy(alpha = 0.92f))))
    }
}

/** Etiqueta pequeña con borde: calidad, sonido, edad… */
@Composable
private fun Tag(text: String) {
    val p = LocalPalette.current
    Box(Modifier.border(1.dp, p.muted.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        T(text, Type.small.copy(fontWeight = FontWeight.SemiBold), p.text)
    }
}

@Composable
private fun ProgressLine(frac: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.height(4.dp).clip(CircleShape).background(p.line)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).background(p.text))
    }
}

private fun hms(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
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

// ---------------- Ficha de película ----------------

@Composable
fun MovieDetail(app: VisorApp, vod: VodStream) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val info = load(vod.id) { app.repo.vodInfo(vod.id) }
    val useTmdb = app.settings.artSource == ArtSource.TMDB && app.tmdb.available
    val tmdb = load(vod.id, useTmdb, info.orNull()?.tmdbId) {
        if (useTmdb) app.tmdb.art(info.orNull()?.tmdbId.orEmpty(), isTv = false) else null
    }.orNull()
    val i = info.orNull()
    val playBtn = remember { FocusRequester() }
    val isFav = app.favorites.contains("vod", vod.id)
    val resume = app.progress.entries.firstOrNull { it.item.key == "vod:${vod.id}" }
    var trailerMsg by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(tmdb?.backdrop?.ifBlank { null } ?: i?.backdrop?.ifBlank { null } ?: i?.cover?.ifBlank { null } ?: vod.icon)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Img(
                tmdb?.poster?.ifBlank { null } ?: i?.cover?.ifBlank { null } ?: vod.icon,
                Modifier.fillMaxHeight(0.8f).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)),
            )
            Column(
                Modifier.fillMaxWidth(0.64f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
            ) {
                T("Película", Type.small, p.muted)
                val title = i?.name?.ifBlank { null } ?: vod.name
                T(title, Type.title, maxLines = 2)
                val orig = i?.originalName.orEmpty()
                if (orig.isNotBlank() && !orig.equals(title, ignoreCase = true)) T("Título original: $orig", Type.small, p.muted)

                val meta = listOfNotNull(
                    (i?.year?.ifBlank { null } ?: vod.year.takeIf { it > 0 }?.toString()),
                    i?.duration?.ifBlank { null },
                    i?.genre?.ifBlank { null },
                    i?.country?.ifBlank { null },
                ).joinToString(" · ")
                if (meta.isNotBlank()) T(meta, Type.body, p.muted, maxLines = 2)

                // Nota y datos técnicos
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    (tmdb?.rating?.ifBlank { null }?.let { "★ $it TMDB" } ?: rating(i?.rating ?: vod.rating))?.let { T(it, Type.label) }
                    i?.age?.ifBlank { null }?.let { Tag(it) }
                    i?.video?.ifBlank { null }?.let { Tag(it) }
                    i?.audio?.ifBlank { null }?.let { Tag(it) }
                }

                val plot = tmdb?.overview?.ifBlank { null } ?: i?.plot.orEmpty()
                when {
                    plot.isNotBlank() -> T(plot, Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), p.text, maxLines = 7)
                    info is Load.Loading -> T("Cargando sinopsis…", Type.body, p.muted)
                    else -> T("El proveedor no incluye sinopsis para esta película.", Type.body, p.muted)
                }
                if (!i?.director.isNullOrBlank()) T("Dirección: ${i?.director}", Type.small, p.muted)
                if (!i?.cast.isNullOrBlank()) T("Reparto: ${i?.cast}", Type.small, p.muted, maxLines = 2)
                if (info is Load.Err) T("No se pudo cargar la ficha completa.", Type.small, p.muted)

                if (resume != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProgressLine(resume.positionMs.toFloat() / resume.durationMs, Modifier.width(220.dp))
                        T("Quedan ${hms(resume.durationMs - resume.positionMs)}", Type.small, p.muted)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val item = PlayItem(kind = "vod", id = vod.id, ext = i?.ext ?: vod.ext, title = vod.name, icon = vod.icon)
                    TvButton(if (resume != null) "▶ Continuar" else "▶ Reproducir", focusRequester = playBtn, onClick = { play(ctx, listOf(item), 0) })
                    if (resume != null) TvButton("Desde el principio", onClick = {
                        app.progress.update(item, 0, 1); play(ctx, listOf(item), 0)
                    })
                    val trailer = i?.trailer.orEmpty()
                    if (trailer.isNotBlank()) TvButton("Tráiler", onClick = {
                        if (!openTrailer(ctx, trailer)) trailerMsg = "No hay ninguna app para abrir el tráiler (instala YouTube)."
                    })
                    TvButton(if (isFav) "★ En favoritos" else "☆ Favoritos", selected = isFav, onClick = { app.favorites.toggle(vod.toFav()) })
                }
                trailerMsg?.let { T(it, Type.small, p.muted) }
            }
        }
    }
    LaunchedEffect(Unit) { try { playBtn.requestFocus() } catch (_: Exception) { } }
}

// ---------------- Ficha de serie ----------------

@Composable
fun SeriesDetail(app: VisorApp, series: SeriesItem) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val info = load(series.id) { app.repo.seriesInfo(series.id) }
    val useTmdb = app.settings.artSource == ArtSource.TMDB && app.tmdb.available
    val tmdb = load(series.id, useTmdb, info.orNull()?.tmdbId) {
        if (useTmdb) app.tmdb.art(info.orNull()?.tmdbId.orEmpty(), isTv = true) else null
    }.orNull()
    val si = info.orNull()
    val isFav = app.favorites.contains("series", series.id)
    val mainBtn = remember { FocusRequester() }
    var trailerMsg by remember { mutableStateOf<String?>(null) }

    // Último episodio a medias de esta serie, para "Continuar"
    val lastWatched = app.progress.entries.filter { it.item.kind == "episode" && it.item.seriesId == series.id }.maxByOrNull { it.updated }
    val allEpisodes = si?.seasons?.values?.flatten().orEmpty()
    // Si el último visto está terminado, "Continuar" propone el siguiente episodio
    val resumeEp = lastWatched?.let { lw ->
        if (lw.done) si?.let { nextEpisode(it, lw.item.id) } else allEpisodes.firstOrNull { it.id == lw.item.id }
    }

    var season by remember { mutableIntStateOf(-1) }
    var focusedEp by remember { mutableStateOf<Episode?>(null) }
    LaunchedEffect(si) {
        if (si != null && season !in si.seasons.keys) season = resumeEp?.season ?: si.seasons.keys.firstOrNull() ?: -1
    }

    fun queueFor(s: Int): List<PlayItem> = si?.seasons?.get(s).orEmpty().map {
        PlayItem(kind = "episode", id = it.id, ext = it.ext, title = "T$s · E${it.num}  ${it.title}",
            subtitle = series.name, icon = it.image.ifBlank { series.cover }, seriesId = series.id)
    }

    fun playEpisode(ep: Episode) {
        val list = si?.seasons?.get(ep.season).orEmpty()
        play(ctx, queueFor(ep.season), list.indexOfFirst { it.id == ep.id }.coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(tmdb?.backdrop?.ifBlank { null } ?: si?.backdrop?.ifBlank { null } ?: si?.cover?.ifBlank { null } ?: series.cover)
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // Cabecera
            T("Serie", Type.small, p.muted)
            T(si?.name?.ifBlank { null } ?: series.name, Type.title, maxLines = 1)
            val nSeasons = si?.seasons?.size ?: 0
            val meta = listOfNotNull(
                si?.year?.ifBlank { null } ?: series.year.takeIf { it > 0 }?.toString(),
                si?.genre?.ifBlank { null },
                if (nSeasons > 0) "$nSeasons ${if (nSeasons == 1) "temporada" else "temporadas"}" else null,
                (tmdb?.rating?.ifBlank { null }?.let { "★ $it TMDB" } ?: rating(si?.rating ?: series.rating)),
            ).joinToString(" · ")
            if (meta.isNotBlank()) T(meta, Type.body, p.muted)
            T(
                tmdb?.overview?.ifBlank { null } ?: si?.plot?.ifBlank { null } ?: series.plot.ifBlank { if (info is Load.Loading) "Cargando…" else "Sin descripción del proveedor." },
                Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), p.text, Modifier.fillMaxWidth(0.62f), maxLines = 3,
            )
            if (!si?.cast.isNullOrBlank()) T("Reparto: ${si?.cast}", Type.small, p.muted, Modifier.fillMaxWidth(0.62f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                val first = si?.seasons?.values?.firstOrNull()?.firstOrNull()
                when {
                    resumeEp != null -> TvButton((if (lastWatched?.done == true) "▶ Siguiente" else "▶ Continuar") + " T${resumeEp.season} · E${resumeEp.num}", focusRequester = mainBtn, onClick = { playEpisode(resumeEp) })
                    first != null -> TvButton("▶ Empezar T${first.season} · E${first.num}", focusRequester = mainBtn, onClick = { playEpisode(first) })
                    else -> TvButton(if (info is Load.Loading) "Cargando episodios…" else "Sin episodios", focusRequester = mainBtn, onClick = {})
                }
                val trailer = si?.trailer.orEmpty()
                if (trailer.isNotBlank()) TvButton("Tráiler", onClick = {
                    if (!openTrailer(ctx, trailer)) trailerMsg = "No hay ninguna app para abrir el tráiler (instala YouTube)."
                })
                TvButton(if (isFav) "★ En favoritos" else "☆ Favoritos", selected = isFav, onClick = { app.favorites.toggle(series.toFav()) })
            }
            trailerMsg?.let { T(it, Type.small, p.muted) }

            Spacer(Modifier.weight(1f))

            // Temporadas
            if (si != null && si.seasons.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(si.seasons.keys.toList()) { _, s ->
                        TvButton("Temporada $s", selected = s == season, onClick = { season = s }, onFocused = { season = s })
                    }
                }
                val eps = si.seasons[season].orEmpty()
                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = eps.indexOfFirst { it.id == resumeEp?.id }.coerceAtLeast(0))
                LazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
                ) {
                    itemsIndexed(eps, key = { _, e -> e.id }) { _, e ->
                        EpisodeCard(app, series, e, onFocused = { focusedEp = e }) { playEpisode(e) }
                    }
                }
                val fe = focusedEp?.takeIf { it.season == season }
                T(
                    fe?.let { listOfNotNull("E${it.num} · ${it.title}", it.duration.ifBlank { null }).joinToString(" · ") + (if (it.plot.isNotBlank()) " — ${it.plot}" else "") }
                        ?: "Elige un episodio para ver de qué va.",
                    // Siempre reserva dos líneas: así la fila de episodios no salta al recorrerla
                    Type.small, p.muted, maxLines = 2, minLines = 2,
                )
            }
        }
    }
    LaunchedEffect(si != null) { if (si != null) try { mainBtn.requestFocus() } catch (_: Exception) { } }
}

@Composable
private fun EpisodeCard(app: VisorApp, series: SeriesItem, e: Episode, onFocused: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    val prog = app.progress.entries.firstOrNull { it.item.key == "episode:${e.id}" }
    Column(Modifier.width(196.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Focusable(onClick = onClick, onFocused = onFocused, shape = RoundedCornerShape(10.dp), scaleOnFocus = true) { focused ->
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .border(if (focused) 3.dp else 0.dp, if (focused) p.focusBg else Color.Transparent, RoundedCornerShape(10.dp))
            ) {
                Img(e.image.ifBlank { series.cover }, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                if (prog != null && !prog.done) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth()) {
                        ProgressLine(prog.positionMs.toFloat() / prog.durationMs)
                    }
                }
            }
        }
        T("E${e.num} · ${e.title}", Type.label, maxLines = 1)
    }
}
