package com.visor.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.visor.tv.ui.Empty
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer

const val SEARCH_FOLDER = "__search"
const val NEW_FOLDER = "__new"

private val DIACRITICS = Regex("\\p{Mn}+")
private val TAGS = Regex("[|\\[\\]()]")

/** Minúsculas, sin tildes ni símbolos: "Película (4K) | ES" → "pelicula 4k es". */
fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").replace(TAGS, " ").lowercase()

/**
 * Buscador sobre la lista completa del proveedor. La lista se descarga una vez (queda en caché)
 * y se filtra en el propio aparato, así que cada letra responde al instante.
 */
@Composable
fun <R> SearchPanel(
    hint: String,
    source: suspend () -> List<R>,
    name: (R) -> String,
    content: @Composable (List<R>) -> Unit,
) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    val all = load(Unit) { withContext(Dispatchers.IO) { source().map { normalize(name(it)) to it } } }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(hint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = p.text, unfocusedBorderColor = p.line,
                focusedLabelColor = p.text, unfocusedLabelColor = p.muted, cursorColor = p.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        LoadView(all) { indexed ->
            val results by produceState<List<R>?>(null, query, indexed) {
                val words = normalize(query).split(' ').filter { it.isNotBlank() }
                if (words.isEmpty() || query.trim().length < 2) { value = null; return@produceState }
                delay(150) // espera a que termine de escribir
                value = withContext(Dispatchers.Default) {
                    indexed.asSequence().filter { (n, _) -> words.all { n.contains(it) } }.map { it.second }.take(300).toList()
                }
            }
            val r = results
            when {
                r == null -> Empty("Escribe al menos 2 letras. Busca en toda la lista (${indexed.size} títulos), no solo en una carpeta.")
                r.isEmpty() -> Empty("Sin resultados para \"${query.trim()}\".")
                else -> {
                    T("${r.size}${if (r.size == 300) "+" else ""} resultados", Type.small, p.muted, Modifier.padding(start = 6.dp))
                    content(r)
                }
            }
        }
    }
}

/** Entradas fijas al principio de la columna de carpetas. */
fun fixedFolders(withNew: Boolean) = buildList {
    add(com.visor.tv.data.Category(SEARCH_FOLDER, "⌕ Buscar"))
    if (withNew) add(com.visor.tv.data.Category(NEW_FOLDER, "✦ Novedades"))
    add(com.visor.tv.data.Category(FAV_FOLDER, "★ Favoritos"))
}
