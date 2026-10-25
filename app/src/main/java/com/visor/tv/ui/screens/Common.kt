package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.window.DialogProperties
import com.visor.tv.data.Category
import com.visor.tv.ui.Empty
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val msg: String) : Load<Nothing>
}

@Suppress("UNCHECKED_CAST")
fun <T> Load<T>.orNull(): T? = (this as? Load.Ok<T>)?.value

@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): Load<T> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = try { Load.Ok(block()) } catch (e: Exception) { Load.Err(e.message ?: "Error al cargar") }
    }.value

@Composable
fun <T> LoadView(state: Load<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Empty("Cargando…", modifier)
        is Load.Err -> Empty("No se pudo cargar: ${state.msg}", modifier)
        is Load.Ok -> content(state.value)
    }
}

const val FAV_FOLDER = "__fav"

/** Punto de entrada de una pantalla con carpetas: al bajar desde las pestañas, el foco va a la carpeta elegida. */
class FolderEntry {
    val fr = FocusRequester()
    var attached by mutableStateOf(false)
    fun target(): FocusRequester = if (attached) fr else FocusRequester.Default
}

/**
 * Para la fila que contiene las carpetas y su contenido: al entrar bajando desde las pestañas,
 * el foco cae siempre en la carpeta elegida, aunque el contenido (pósters, canales) quede más cerca.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.enterAtFolders(entry: FolderEntry): Modifier = this
    .focusProperties { enter = { dir -> if (dir == FocusDirection.Down) entry.target() else FocusRequester.Default } }
    .focusGroup()

/** Columna de carpetas del proveedor. Al enfocar una carpeta se abre su contenido. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun FolderColumn(
    folders: List<Category>,
    selected: String?,
    onSelect: (String) -> Unit,
    width: Dp = 210.dp,
    entry: FolderEntry = remember { FolderEntry() },
) {
    val p = LocalPalette.current
    // Al bajar desde las pestañas se entra en la carpeta elegida (o en la primera que no sea Buscar),
    // nunca directamente en el buscador; a Buscar se llega subiendo.
    val targetId = selected?.takeIf { it != SEARCH_FOLDER } ?: folders.firstOrNull { it.id != SEARCH_FOLDER }?.id
    val target = entry.fr
    Column(Modifier.width(width).fillMaxHeight()) {
        T("Carpetas", Type.small, p.muted, Modifier.padding(start = 12.dp, bottom = 6.dp))
        LazyColumn(
            modifier = Modifier.focusProperties { enter = { entry.target() } },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(folders, key = { it.id }) { c ->
                val isTarget = c.id == targetId
                if (isTarget) {
                    DisposableEffect(c.id) {
                        entry.attached = true
                        onDispose { entry.attached = false }
                    }
                }
                ListRow(
                    title = c.name,
                    selected = c.id == selected,
                    onClick = { onSelect(c.id) },
                    onFocused = { onSelect(c.id) },
                    focusRequester = if (isTarget) target else null,
                )
            }
        }
    }
}

fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale("es")).format(Date(ms))
fun dayHm(ms: Long): String = SimpleDateFormat("EEE d MMM · HH:mm", Locale("es")).format(Date(ms))

/** Diálogo con una lista de acciones para el mando. */
@Composable
fun ActionsDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit, subtitle: String? = null) {
    val p = LocalPalette.current
    val first = remember { FocusRequester() }
    // Sin el ancho por defecto del sistema, que en Fire TV es estrecho y cortaba los botones
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(480.dp).heightIn(max = 470.dp).clip(RoundedCornerShape(20.dp)).background(p.surface)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            T(title, Type.h2, maxLines = 2, modifier = Modifier.padding(horizontal = 6.dp))
            if (subtitle != null) T(subtitle, Type.small, p.muted, maxLines = 4, modifier = Modifier.padding(horizontal = 6.dp))
            Box(Modifier.size(6.dp))
            actions.forEachIndexed { i, (label, action) ->
                MenuRow(label, if (i == 0) first else null) { action(); onDismiss() }
            }
        }
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}

/**
 * FocusRequester para un elemento del catálogo. Si es el elemento desde el que se abrió la ficha,
 * recupera el foco al volver, para seguir navegando desde el mismo sitio.
 */
@Composable
fun rememberReturnFocus(nav: com.visor.tv.Nav, key: String): FocusRequester {
    val fr = remember { FocusRequester() }
    if (nav.returnKey == key) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            try { fr.requestFocus() } catch (_: Exception) { }
            nav.returnKey = null
        }
    }
    return fr
}

fun List<com.visor.tv.data.VodStream>.sortedFor(s: com.visor.tv.data.SortBy) = when (s) {
    com.visor.tv.data.SortBy.NEWEST -> sortedWith(compareByDescending<com.visor.tv.data.VodStream> { it.added }.thenByDescending { it.id })
    com.visor.tv.data.SortBy.OLDEST -> sortedWith(compareBy<com.visor.tv.data.VodStream> { it.added }.thenBy { it.id })
    com.visor.tv.data.SortBy.YEAR_DESC -> sortedWith(compareByDescending<com.visor.tv.data.VodStream> { it.year }.thenByDescending { it.added })
    com.visor.tv.data.SortBy.YEAR_ASC -> sortedWith(compareBy<com.visor.tv.data.VodStream> { if (it.year == 0) Int.MAX_VALUE else it.year }.thenBy { it.added })
    com.visor.tv.data.SortBy.AZ -> sortedBy { normalize(it.name) }
    com.visor.tv.data.SortBy.ZA -> sortedByDescending { normalize(it.name) }
}

@JvmName("sortedSeries")
fun List<com.visor.tv.data.SeriesItem>.sortedFor(s: com.visor.tv.data.SortBy) = when (s) {
    com.visor.tv.data.SortBy.NEWEST -> sortedWith(compareByDescending<com.visor.tv.data.SeriesItem> { it.updated }.thenByDescending { it.id })
    com.visor.tv.data.SortBy.OLDEST -> sortedWith(compareBy<com.visor.tv.data.SeriesItem> { it.updated }.thenBy { it.id })
    com.visor.tv.data.SortBy.YEAR_DESC -> sortedWith(compareByDescending<com.visor.tv.data.SeriesItem> { it.year }.thenByDescending { it.updated })
    com.visor.tv.data.SortBy.YEAR_ASC -> sortedWith(compareBy<com.visor.tv.data.SeriesItem> { if (it.year == 0) Int.MAX_VALUE else it.year }.thenBy { it.updated })
    com.visor.tv.data.SortBy.AZ -> sortedBy { normalize(it.name) }
    com.visor.tv.data.SortBy.ZA -> sortedByDescending { normalize(it.name) }
}

/** Botón "Ordenar: …" que abre la lista de órdenes posibles. */
@Composable
fun SortButton(current: com.visor.tv.data.SortBy, onPick: (com.visor.tv.data.SortBy) -> Unit) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    TvButton("Ordenar: ${current.label}  ▾", onClick = { open = true })
    if (open) {
        ActionsDialog(
            "Ordenar por",
            com.visor.tv.data.SortBy.entries.map { o -> ((if (o == current) "✓ " else "") + o.label) to { onPick(o) } },
            onDismiss = { open = false },
        )
    }
}


/** Opción de un menú: ocupa todo el ancho y admite dos líneas, para que nunca se corte. */
@Composable
fun MenuRow(label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    com.visor.tv.ui.Focusable(
        onClick = onClick,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (focused) p.focusBg else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            T(label, Type.label.copy(fontSize = 15.sp), if (focused) p.focusText else p.text, maxLines = 2)
        }
    }
}
