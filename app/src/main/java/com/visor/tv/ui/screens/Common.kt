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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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

/** Columna de carpetas del proveedor. Al enfocar una carpeta se abre su contenido. */
@Composable
fun FolderColumn(
    folders: List<Category>,
    selected: String?,
    onSelect: (String) -> Unit,
    width: Dp = 210.dp,
) {
    val p = LocalPalette.current
    Column(Modifier.width(width).fillMaxHeight()) {
        T("Carpetas", Type.small, p.muted, Modifier.padding(start = 12.dp, bottom = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(folders, key = { it.id }) { c ->
                ListRow(
                    title = c.name,
                    selected = c.id == selected,
                    onClick = { onSelect(c.id) },
                    onFocused = { onSelect(c.id) },
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
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).clip(RoundedCornerShape(20.dp)).background(p.surface)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            T(title, Type.h2, maxLines = 2)
            if (subtitle != null) T(subtitle, Type.small, p.muted, maxLines = 3)
            Box(Modifier.size(4.dp))
            actions.forEachIndexed { i, (label, action) ->
                TvButton(
                    text = label,
                    onClick = { action(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (i == 0) first else null,
                )
            }
        }
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}
