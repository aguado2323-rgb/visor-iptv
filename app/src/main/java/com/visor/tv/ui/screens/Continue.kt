package com.visor.tv.ui.screens

import androidx.compose.foundation.background
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
                    image = info?.backdrop?.ifBlank { null } ?: it.icon, item = it,
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
                        item = it, episode = next, season = next.season, route = Route.Show(show),
                    )
                } else {
                    val ep = si.seasons.values.flatten().firstOrNull { x -> x.id == it.id } ?: continue
                    out += ContinueCard(
                        key = "s:${it.seriesId}", title = name, sub = "T${ep.season} · E${ep.num} · ${ep.title}",
                        note = left(e.durationMs - e.positionMs), frac = e.positionMs.toFloat() / e.durationMs,
                        image = ep.image.ifBlank { si.backdrop.ifBlank { si.cover } },
                        item = it, episode = ep, season = ep.season, route = Route.Show(show),
                    )
                }
            }
        }
    }
    return out
}

/** Fila "Seguir viendo" de Inicio. OK reanuda donde lo dejaste; ≡ ver ficha o quitar. */
@Composable
fun ContinueRow(app: VisorApp, nav: Nav, cards: List<ContinueCard>, cardH: Dp) {
    val p = LocalPalette.current
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
        modifier = Modifier.height(cardH + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        items(cards, key = { it.key }) { c ->
            Focusable(
                onClick = { resume(c) },
                onMenu = { menuFor = c },
                focusRequester = rememberReturnFocus(nav, "home:cw:${c.key}"),
                shape = RoundedCornerShape(14.dp),
                scaleOnFocus = true,
            ) { focused ->
                val compact = cardH < 64.dp
                Row(
                    Modifier.width((cardH * 3.55f).coerceAtLeast(250.dp)).height(cardH)
                        .background(if (focused) p.focusBg else p.surface).padding(if (compact) 6.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                        Img(c.image, Modifier.fillMaxSize())
                        if (c.frac > 0f) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.35f))) {
                                Box(Modifier.fillMaxWidth(c.frac.coerceIn(0f, 1f)).height(4.dp).background(Color.White))
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val fg = if (focused) p.focusText else p.text
                        T(c.title, Type.label, fg, maxLines = 1)
                        if (compact) {
                            // Tira fina: episodio y tiempo restante en una sola línea
                            T("${c.sub} · ${c.note}", Type.small, fg.copy(alpha = 0.75f), maxLines = 1)
                            return@Column
                        }
                        T(c.sub, Type.small, fg.copy(alpha = 0.75f), maxLines = 1)
                        T(c.note, Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), if (focused) fg else p.muted, Modifier.padding(top = 4.dp), maxLines = 1)
                        if (focused) T("OK reanudar · ≡ más", Type.small, fg.copy(alpha = 0.6f), maxLines = 1)
                    }
                }
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
