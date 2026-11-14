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

// Descubrir: películas y series de los addons que tengas instalados (compatibles con Stremio y Nuvio).
// Arriba, lo que tienes seleccionado a pantalla completa; abajo, una fila por catálogo. Siempre en oscuro.

/** Favorito ↔ título de Descubrir. */
fun MetaPreview.toFav() = FavItem(type = "addon", id = 0, name = name, icon = poster, ext = year, ref = key)
fun FavItem.toMeta() = MetaPreview(type = ref.substringBefore('/'), id = ref.substringAfter('/'), name = name, poster = icon, year = ext)

/** Imagen apaisada: la del addon o, para títulos de IMDb, la del banco de imágenes que usa Cinemeta. */
internal fun MetaPreview.backdropUrl(): String = background.ifBlank {
    if (id.startsWith("tt")) "https://images.metahub.space/background/medium/${id.substringBefore(':')}/img" else ""
}

internal fun MetaPreview.logoUrl(): String = logo.ifBlank {
    if (id.startsWith("tt")) "https://images.metahub.space/logo/medium/${id.substringBefore(':')}/img" else ""
}

internal fun MetaPreview.line(): String = listOfNotNull(
    if (isSeries) "Serie" else "Película",
    year.ifBlank { null },
    genres.ifBlank { null },
    rating.ifBlank { null }?.let { "★ $it" },
).joinToString("  ·  ")

/** Lo que tienes a medias en Descubrir: una tarjeta por película o por serie. */
private data class Resume(val meta: MetaPreview, val note: String, val frac: Float, val item: PlayItem)

private fun resumes(app: VisorApp): List<Resume> =
    app.progress.entries.filter { it.item.kind == "addon" && it.item.parent.isNotBlank() }
        .sortedByDescending { it.updated }
        .distinctBy { it.item.parent }
        .map { e ->
            val it = e.item
            val ep = if (it.isEpisode) "T${it.season} · E${it.episode}" else null
            val left = ((e.durationMs - e.positionMs) / 60_000).coerceAtLeast(1)
            val leftText = if (left >= 60) "quedan ${left / 60} h ${left % 60} min" else "quedan $left min"
            Resume(
                meta = MetaPreview(
                    type = it.parent.substringBefore('/'), id = it.parent.substringAfter('/'),
                    name = if (it.isEpisode) it.subtitle.ifBlank { it.title } else it.title, poster = it.icon,
                ),
                note = if (e.done) listOfNotNull(ep, "visto").joinToString(" · ") else listOfNotNull(ep, leftText).joinToString(" · "),
                frac = if (e.done || e.durationMs <= 0) 0f else e.positionMs.toFloat() / e.durationMs,
                item = it,
            )
        }

@Composable
private fun DiscoverBackdrop(meta: MetaPreview?) {
    // Espera a que el foco se pare: al recorrer una fila no se descarga la imagen de cada título
    val shown by produceState<MetaPreview?>(null, meta?.key) { delay(280); value = meta }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = shown, animationSpec = tween(450), label = "fondo") { m ->
            Box(Modifier.fillMaxSize()) {
                val wide = m?.backdropUrl().orEmpty()
                when {
                    wide.isNotBlank() -> Img(wide, Modifier.fillMaxSize())
                    !m?.poster.isNullOrBlank() -> Img(m!!.poster, Modifier.fillMaxHeight().aspectRatio(2f / 3f).align(Alignment.CenterEnd))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.94f), 0.36f to Color.Black.copy(alpha = 0.66f), 0.68f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.30f to Color.Transparent, 0.58f to Color.Black.copy(alpha = 0.84f), 0.74f to Color.Black)))
        Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent)))
    }
}

/** Píldora del filtro de arriba (Buscar, Todo, Películas, Series…). */
@Composable
private fun FilterPill(text: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(50)) { focused ->
        Box(
            Modifier
                .background(if (focused) Color.White else if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            T(text, Type.label.copy(fontSize = 12.5.sp), if (focused) Color.Black else Color.White.copy(alpha = if (selected) 1f else 0.82f))
        }
    }
}

@Composable
fun DiscoverScreen(app: VisorApp, nav: Nav) = CompositionLocalProvider(LocalPalette provides OledPalette) {
    val d = app.discover
    var filter by rememberSaveable { mutableStateOf("") } // "", "movie" o "series"
    var searching by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf<MetaPreview?>(null) }
    val rows = remember(d.version, filter) { d.rows(filter.ifBlank { null }) }
    val resume = resumes(app).filter { filter.isBlank() || it.meta.type == filter }
    val mine = app.favorites.of("addon").map { it.toMeta() }.filter { filter.isBlank() || it.type == filter }

    Box(Modifier.fillMaxSize()) {
        DiscoverBackdrop(if (searching) null else focused)
        Column(Modifier.fillMaxSize().padding(top = 60.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("⌕  Buscar", searching) { searching = !searching }
                FilterPill("Todo", !searching && filter == "") { searching = false; filter = "" }
                FilterPill("Películas", !searching && filter == "movie") { searching = false; filter = "movie" }
                FilterPill("Series", !searching && filter == "series") { searching = false; filter = "series" }
                Spacer(Modifier.weight(1f))
                FilterPill("Addons y debrid", false, rememberReturnFocus(nav, "disc:setup")) { nav.open(Route.DiscoverSetup, "disc:setup") }
            }

            if (searching) {
                BackHandler { searching = false }
                DiscoverSearch(app, nav)
            } else {

                // ---- Lo seleccionado: siempre el mismo hueco, para que las filas no salten ----
                Column(Modifier.padding(start = 48.dp, top = 14.dp).width(560.dp).height(138.dp)) {
                    val f = focused
                    if (f != null) {
                        TitleArt(f.name, f.logoUrl(), logoHeight = 52.dp, style = BIG_TITLE.copy(fontSize = 28.sp, lineHeight = 32.sp))
                        T(f.line(), META.copy(fontSize = 12.sp), Color(0xFFD6D6D0), Modifier.padding(top = 6.dp))
                        if (f.description.isNotBlank()) T(f.description, Type.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp), Color(0xFFE0E0DC), Modifier.padding(top = 6.dp), maxLines = 2)
                    } else {
                        T("DESCUBRIR", EYEBROW, Color(0xFFC9C9C4))
                        T("Películas y series de tus addons", BIG_TITLE.copy(fontSize = 28.sp, lineHeight = 32.sp), Color.White, Modifier.padding(top = 4.dp))
                        T(
                            if (d.hasStreamAddon) "Elige un título para ver su ficha y sus fuentes."
                            else "Los catálogos ya se pueden recorrer. Para reproducir, añade un addon de fuentes y tu cuenta debrid en «Addons y debrid».",
                            Type.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp), Color(0xFFE0E0DC), Modifier.padding(top = 6.dp), maxLines = 2,
                        )
                    }
                }
                if (!d.hasStreamAddon && focused != null) {
                    T("Sin addon de fuentes todavía: se ven las fichas, pero no se puede reproducir. Añádelo en «Addons y debrid».", Type.small, MUTED, Modifier.padding(start = 48.dp, bottom = 4.dp))
                }

                if (rows.isEmpty() && resume.isEmpty() && mine.isEmpty()) {
                    Empty("No hay catálogos que mostrar. Añade o activa un addon con catálogos en «Addons y debrid».", Modifier.padding(start = 24.dp))
                } else LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
                ) {
                    if (resume.isNotEmpty()) item(key = "resume") {
                        RowTitle("Seguir viendo")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
                            items(resume, key = { it.meta.key }) { r ->
                                val k = "disc:cw:${r.meta.key}"
                                var menu by remember { mutableStateOf(false) }
                                Poster(
                                    url = r.meta.poster, title = r.meta.name, subtitle = r.note, width = 108.dp,
                                    progress = r.frac.takeIf { it > 0f },
                                    onClick = { nav.open(Route.Addon(r.meta), k) },
                                    onMenu = { menu = true },
                                    onFocused = { focused = r.meta },
                                    focusRequester = rememberReturnFocus(nav, k),
                                )
                                if (menu) ActionsDialog(
                                    r.meta.name,
                                    listOf("Ver ficha" to { nav.open(Route.Addon(r.meta), k) }, "Quitar de Seguir viendo" to { app.progress.remove(r.item) }),
                                    onDismiss = { menu = false },
                                    subtitle = r.note,
                                )
                            }
                        }
                    }
                    if (mine.isNotEmpty()) item(key = "mine") {
                        RowTitle("Mi lista")
                        MetaRow(app, nav, mine, "disc:fav") { focused = it }
                    }
                    items(rows, key = { it.key }) { row ->
                        val state = load(row.key, d.version) { d.catalog(row) }
                        when (state) {
                            Load.Loading -> { RowTitle(row.title); T("Cargando…", Type.small, MUTED, Modifier.padding(start = 48.dp, top = 8.dp).height(150.dp)) }
                            is Load.Err -> { RowTitle(row.title); T("${row.addon.manifest.name}: ${state.msg}", Type.small, MUTED, Modifier.padding(start = 48.dp, top = 8.dp, bottom = 8.dp)) }
                            is Load.Ok -> if (state.value.isNotEmpty()) {
                                RowTitle(row.title, row.addon.manifest.name)
                                MetaRow(app, nav, state.value, "disc:${row.key}") { focused = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowTitle(title: String, source: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, SECTION, Color.White, Modifier.weight(1f))
        if (source != null) T(source, Type.small, MUTED)
    }
}

/** Fila de carátulas de Descubrir. OK abre la ficha; ≡ añade o quita de Mi lista. */
@Composable
private fun MetaRow(app: VisorApp, nav: Nav, list: List<MetaPreview>, prefix: String, onFocus: (MetaPreview) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp)) {
        items(list, key = { it.key }) { m ->
            val k = "$prefix:${m.key}"
            Poster(
                url = m.poster, title = m.name, width = 108.dp,
                subtitle = listOfNotNull(if (app.favorites.containsRef(m.key)) "★" else null, m.year.ifBlank { null }, m.rating.ifBlank { null }?.let { "★ $it" }).joinToString("  ").ifBlank { null },
                onClick = { nav.open(Route.Addon(m), k) },
                onMenu = { app.favorites.toggle(m.toFav()) },
                onFocused = { onFocus(m) },
                focusRequester = rememberReturnFocus(nav, k),
            )
        }
    }
}

/** Cuadrícula de carátulas de Descubrir (resultados de búsqueda, favoritos). */
@Composable
fun MetaGrid(app: VisorApp, nav: Nav, list: List<MetaPreview>, prefix: String, modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(6.dp)) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = padding,
    ) {
        items(list, key = { it.key }) { m ->
            val k = "$prefix:${m.key}"
            Poster(
                url = m.poster, title = m.name,
                subtitle = listOfNotNull(
                    if (app.favorites.containsRef(m.key)) "★" else null,
                    if (m.isSeries) "Serie" else "Película",
                    m.year.ifBlank { null },
                ).joinToString("  "),
                onClick = { nav.open(Route.Addon(m), k) },
                onMenu = { app.favorites.toggle(m.toFav()) },
                focusRequester = rememberReturnFocus(nav, k),
            )
        }
    }
}

/** Buscador: pregunta a todos los addons que admiten búsqueda. */
@Composable
private fun DiscoverSearch(app: VisorApp, nav: Nav) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val q = query.trim()
    val results = load(q, app.discover.version) {
        if (q.length < 2) null else { delay(450); app.discover.search(q) } // espera a que termine de escribir
    }

    fun toResults() { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar películas y series") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { toResults() }, onDone = { toResults() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = p.text, unfocusedBorderColor = p.line,
                focusedLabelColor = p.text, unfocusedLabelColor = p.muted, cursorColor = p.text,
            ),
            modifier = Modifier
                .width(520.dp)
                .focusRequester(field)
                // La caja de texto se queda las flechas del mando: aquí se las devolvemos a la navegación
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { toResults(); true }
                        Key.DirectionUp -> { keyboard?.hide(); focus.moveFocus(FocusDirection.Up); true }
                        else -> false
                    }
                },
        )
        when (results) {
            Load.Loading -> T("Buscando…", Type.body, MUTED)
            is Load.Err -> T("No se pudo buscar: ${results.msg}", Type.body, MUTED)
            is Load.Ok -> {
                val list = results.value
                when {
                    list == null -> T("Escribe al menos dos letras. Se busca en todos tus addons con catálogo.", Type.body, MUTED)
                    list.isEmpty() -> T("Sin resultados para «$q».", Type.body, MUTED)
                    else -> MetaGrid(app, nav, list, "disc:q", Modifier.fillMaxSize(), PaddingValues(top = 6.dp, bottom = 28.dp, start = 4.dp, end = 4.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (nav.returnKey == null) try { field.requestFocus() } catch (_: Exception) { } }
}
