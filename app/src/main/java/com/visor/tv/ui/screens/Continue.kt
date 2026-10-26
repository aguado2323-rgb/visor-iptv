package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.Episode
import com.visor.tv.data.PlayItem
import com.visor.tv.data.SeriesInfo
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.VodStream
import com.visor.tv.play
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.Img
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import kotlinx.coroutines.launch

/** El episodio que va después de [epId], pasando a la temporada siguiente si hace falta. */
fun nextEpisode(si: SeriesInfo, epId: Int): Episode? {
    val all = si.seasons.toSortedMap().values.flatMap { eps -> eps.sortedBy { it.num } }
    val i = all.indexOfFirst { it.id == epId }
    return if (i >= 0) all.getOrNull(i + 1) else null
}

/** Episodios de una temporada como cola de reproducción (así funciona "siguiente episodio"). */
fun seasonQueue(seriesId: Int, seriesName: String, seriesCover: String, si: SeriesInfo, season: Int): List<PlayItem> =
    si.seasons[season].orEmpty().map {
        PlayItem(
            kind = "episode", id = it.id, ext = it.ext, title = "T$season · E${it.num}  ${it.title}",
            subtitle = seriesName, icon = it.image.ifBlank { seriesCover }, seriesId = seriesId,
        )
    }

/** Una tarjeta de "Seguir viendo". */
data class ContinueCard(
    val key: String,
    val title: String,
    val sub: String,
    val note: String,
    val frac: Float,
    val image: String,
    /** Lo que se quita con "Quitar de Seguir viendo". */
    val item: PlayItem,
    val episode: Episode? = null,
    val season: Int = 0,
    val route: Route,
    /** Una sola línea para la tira fina: "T2 · E3 · quedan 18 min", "Siguiente: T1 · E7"… */
    val short: String = sub,
    /** Sinopsis (de la película o del episodio), para la parte de arriba de Inicio. */
    val plot: String = "",
    /** Carátula vertical, por si no hay imagen apaisada. */
    val poster: String = "",
)

private fun left(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return "Quedan " + if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/**
 * Lo que tienes a medias, lo más reciente primero: películas sin terminar y, de cada serie, el episodio
 * a medias o el siguiente al último que acabaste. No incluye contenido de carpetas bloqueadas.
 */
suspend fun buildContinue(app: VisorApp, max: Int = 10): List<ContinueCard> {
    val entries = app.progress.entries.toList().sortedByDescending { it.updated }
        .distinctBy { if (it.item.kind == "episode" && it.item.seriesId > 0) "s:${it.item.seriesId}" else it.item.key }

    // Carpeta de cada título, solo si hay algo bloqueado (las listas completas ya están en caché)
    val vodBlocked = app.folders.excludesLocked("vod")
    val seriesBlocked = app.folders.excludesLocked("series")
    val vodCat = if (vodBlocked != null) try { app.repo.allVod().associate { it.id to it.categoryId } } catch (e: Exception) { emptyMap() } else emptyMap()
    val seriesCat = if (seriesBlocked != null) try { app.repo.allSeries().associate { it.id to it.categoryId } } catch (e: Exception) { emptyMap() } else emptyMap()

    val out = mutableListOf<ContinueCard>()
    for (e in entries) {
        if (out.size >= max) break
        val it = e.item
        when (it.kind) {
            "vod" -> {
                if (e.done) continue
                if (vodBlocked != null && vodCat[it.id]?.let(vodBlocked) == true) continue
                val info = try { app.repo.vodInfo(it.id) } catch (x: Exception) { null }
                out += ContinueCard(
                    key = it.key, title = it.title, sub = listOfNotNull("Película", info?.year?.ifBlank { null }).joinToString(" · "),
                    note = left(e.durationMs - e.positionMs), frac = e.positionMs.toFloat() / e.durationMs,
                    image = info?.backdrop?.ifBlank { null } ?: it.icon, item = it, short = left(e.durationMs - e.positionMs),
                    plot = info?.plot.orEmpty(), poster = info?.cover?.ifBlank { null } ?: it.icon,
                    route = Route.Movie(VodStream(id = it.id, name = it.title, icon = it.icon, rating = "", ext = it.ext, categoryId = "")),
                )
            }
            "episode" -> {
                if (it.seriesId <= 0) continue
                if (seriesBlocked != null && seriesCat[it.seriesId]?.let(seriesBlocked) == true) continue
                val si = try { app.repo.seriesInfo(it.seriesId) } catch (x: Exception) { null } ?: continue
                val name = it.subtitle.ifBlank { si.name }
                val show = SeriesItem(id = it.seriesId, name = name, cover = si.cover, rating = si.rating, plot = si.plot, categoryId = "")
                if (e.done) {
                    val next = nextEpisode(si, it.id) ?: continue
                    out += ContinueCard(
                        key = "s:${it.seriesId}", title = name, sub = "Siguiente: T${next.season} · E${next.num}",
                        note = "Episodio nuevo", frac = 0f, image = next.image.ifBlank { si.backdrop.ifBlank { si.cover } },
                        item = it, episode = next, season = next.season, route = Route.Show(show), short = "Siguiente: T${next.season} · E${next.num}",
                        plot = next.plot.ifBlank { si.plot }, poster = si.cover,
                    )
                } else {
                    val ep = si.seasons.values.flatten().firstOrNull { x -> x.id == it.id } ?: continue
                    out += ContinueCard(
                        key = "s:${it.seriesId}", title = name, sub = "T${ep.season} · E${ep.num} · ${ep.title}",
                        note = left(e.durationMs - e.positionMs), frac = e.positionMs.toFloat() / e.durationMs,
                        image = ep.image.ifBlank { si.backdrop.ifBlank { si.cover } },
                        item = it, episode = ep, season = ep.season, route = Route.Show(show),
                        short = "T${ep.season} · E${ep.num} · " + left(e.durationMs - e.positionMs).replaceFirstChar { c -> c.lowercase() },
                        plot = ep.plot.ifBlank { si.plot }, poster = si.cover,
                    )
                }
            }
        }
    }
    return out
}

/**
 * Fila "Seguir viendo" de Inicio: miniaturas apaisadas con su barra de progreso.
 * OK reanuda donde lo dejaste; ≡ ver ficha o quitar. [onFocusCard] avisa para cambiar el fondo.
 */
@Composable
fun ContinueRow(
    app: VisorApp, nav: Nav, cards: List<ContinueCard>,
    onFocusCard: (ContinueCard) -> Unit = {},
    itemModifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<ContinueCard?>(null) }

    fun resume(c: ContinueCard) {
        val ep = c.episode
        if (ep == null) {
            play(ctx, listOf(c.item), 0)
            return
        }
        scope.launch {
            val si = try { app.repo.seriesInfo(c.item.seriesId) } catch (e: Exception) { null } ?: return@launch
            val queue = seasonQueue(c.item.seriesId, c.title, si.cover, si, c.season)
            play(ctx, queue, queue.indexOfFirst { it.id == ep.id }.coerceAtLeast(0))
        }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 4.dp),
    ) {
        items(cards, key = { it.key }) { c ->
            Column(Modifier.width(200.dp)) {
                Focusable(
                    onClick = { resume(c) },
                    onMenu = { menuFor = c },
                    onFocused = { onFocusCard(c) },
                    focusRequester = rememberReturnFocus(nav, "home:cw:${c.key}"),
                    shape = RoundedCornerShape(10.dp),
                    scaleOnFocus = true,
                    modifier = itemModifier,
                ) { focused ->
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                    ) {
                        Img(c.image, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                        if (c.frac > 0f) {
                            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                                Box(Modifier.fillMaxWidth(c.frac.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                            }
                        } else if (c.episode != null) {
                            Box(Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                T("NUEVO EPISODIO", Type.small.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), Color.Black)
                            }
                        }
                    }
                }
                T(c.title, Type.label, Color.White, Modifier.padding(top = 7.dp), maxLines = 1)
                T(c.short, Type.small, Color(0xFF9A9DA3), maxLines = 1)
            }
        }
    }

    menuFor?.let { c ->
        ActionsDialog(
            c.title,
            listOf(
                (if (c.episode != null && c.frac == 0f) "▶ Ver el siguiente" else "▶ Reanudar") to { resume(c) },
                "Ver ficha" to { nav.open(c.route, "home:cw:${c.key}") },
                "Quitar de Seguir viendo" to { app.progress.remove(c.item) },
            ),
            onDismiss = { menuFor = null },
            subtitle = c.sub,
        )
    }
}
