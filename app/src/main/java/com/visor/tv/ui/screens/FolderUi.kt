package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visor.tv.VisorApp
import com.visor.tv.data.Category
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type

/** Para la columna de carpetas: de qué tipo son y la lista completa del proveedor (también las ocultas). */
data class FolderManage(val type: String, val all: List<Category>)

/** Carpetas del proveedor en el orden elegido y sin las ocultas. Se recompone al cambiar algo. */
@Composable
fun arrangedFolders(app: VisorApp, type: String, cats: List<Category>): List<Category> {
    app.folders.version // lectura de estado: al ocultar o mover, la lista se vuelve a calcular
    return app.folders.arrange(type, cats)
}

/** La carpeta elegida está bloqueada y aún no se ha abierto con el PIN. */
@Composable
fun isBlockedFolder(app: VisorApp, type: String, id: String): Boolean {
    app.folders.version
    return id != SEARCH_FOLDER && id != NEW_FOLDER && id != FAV_FOLDER && app.folders.isBlocked(type, id)
}

/** Nombre de carpeta con su candado, si está bloqueada. */
fun folderLabel(app: VisorApp, type: String, c: Category): String = when {
    !app.folders.isLocked(type, c.id) -> c.name
    app.folders.isUnlocked(type, c.id) -> "🔓 " + c.name
    else -> "🔒 " + c.name
}

/** Teclado de PIN en una ventana. */
@Composable
fun PinDialog(title: String, subtitle: String? = null, onDone: (String) -> Boolean, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(420.dp).height(440.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(16.dp)) {
            PinPad(title, onDone = onDone, subtitle = subtitle, onCancel = onDismiss)
        }
    }
}

/** Pide el PIN y abre una carpeta bloqueada hasta que salgas de la app. */
@Composable
fun UnlockFolderDialog(app: VisorApp, type: String, id: String, onDismiss: () -> Unit) {
    PinDialog("Carpeta bloqueada", "Introduce el PIN para abrirla", onDone = { pin ->
        if (app.vault.checkPin(pin)) { app.folders.unlock(type, id); onDismiss(); true } else false
    }, onDismiss = onDismiss)
}

/** Lo que se ve a la derecha cuando la carpeta elegida está bloqueada. */
@Composable
fun LockedPanel(app: VisorApp, type: String, id: String) {
    val p = LocalPalette.current
    var ask by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        T("🔒 Carpeta bloqueada", Type.heading)
        T("Pulsa OK sobre la carpeta o aquí para abrirla con el PIN. Se vuelve a bloquear al salir de la app.", Type.body, p.muted, maxLines = 3)
        TvButton("Abrir con PIN", onClick = { ask = true })
    }
    if (ask) UnlockFolderDialog(app, type, id) { ask = false }
}

/**
 * Menú ≡ de una carpeta: ocultar, mover y bloquear. [onHidden] se llama al ocultarla,
 * para que la pantalla pase a otra carpeta.
 */
@Composable
fun FolderMenu(app: VisorApp, m: FolderManage, c: Category, onDismiss: () -> Unit, onHidden: () -> Unit = {}) {
    val f = app.folders
    var step by remember { mutableStateOf("menu") } // menu | newpin | unlockpin
    when (step) {
        "menu" -> {
            val hidden = f.isHidden(m.type, c.id)
            val locked = f.isLocked(m.type, c.id)
            val acts = buildList<Pair<String, () -> Unit>> {
                add((if (hidden) "Mostrar carpeta" else "Ocultar carpeta") to {
                    f.setHidden(m.type, c.id, !hidden); if (!hidden) onHidden()
                })
                add("Subir" to { f.move(m.type, c.id, m.all, delta = -1) })
                add("Bajar" to { f.move(m.type, c.id, m.all, delta = 1) })
                add("Mover al principio" to { f.move(m.type, c.id, m.all, toTop = true) })
                if (locked) add("Quitar el bloqueo" to {})
                else add("Bloquear con PIN" to { if (app.vault.hasPin()) f.setLocked(m.type, c.id, true) })
            }
            // Las opciones que piden PIN cambian de paso en vez de cerrar
            ActionsDialog(
                c.name,
                acts.map { (label, action) ->
                    label to {
                        when {
                            label == "Quitar el bloqueo" -> step = "unlockpin"
                            label == "Bloquear con PIN" && !app.vault.hasPin() -> step = "newpin"
                            else -> action()
                        }
                    }
                },
                onDismiss = { if (step == "menu") onDismiss() },
                subtitle = listOfNotNull(if (hidden) "Oculta" else null, if (locked) "Bloqueada con PIN" else null).joinToString(" · ").ifBlank { null },
            )
        }
        "newpin" -> PinDialog(
            "Crea un PIN de 4 cifras",
            "Lo pedirá esta carpeta. También protegerá Ajustes.",
            onDone = { pin -> app.vault.setPin(pin); f.setLocked(m.type, c.id, true); onDismiss(); true },
            onDismiss = onDismiss,
        )
        "unlockpin" -> PinDialog(
            "Quitar el bloqueo",
            "Introduce el PIN",
            onDone = { pin -> if (app.vault.checkPin(pin)) { f.setLocked(m.type, c.id, false); onDismiss(); true } else false },
            onDismiss = onDismiss,
        )
    }
}

private val TYPE_LABELS = listOf("live" to "TV", "vod" to "Películas", "series" to "Series")

/** Ajustes → Carpetas: todas las carpetas de un tipo para mostrarlas, ocultarlas, ordenarlas o bloquearlas. */
@Composable
fun FolderManagerDialog(app: VisorApp, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var type by remember { mutableStateOf("live") }
    var menuFor by remember { mutableStateOf<Category?>(null) }
    val first = remember { FocusRequester() }
    val cats = load(type, app.repo.version) {
        when (type) {
            "live" -> app.repo.liveCategories()
            "vod" -> app.repo.vodCategories()
            else -> app.repo.seriesCategories()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(640.dp).height(480.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("Carpetas", Type.h2)
            T("OK muestra u oculta · ≡ mover o bloquear con PIN. Las nuevas del proveedor aparecen al final.", Type.small, p.muted, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TYPE_LABELS.forEachIndexed { i, (t, label) ->
                    TvButton(label, selected = type == t, focusRequester = if (i == 0) first else null, onClick = { type = t }, onFocused = { type = t })
                }
            }
            LoadView(cats) { list ->
                app.folders.version
                val all = app.folders.arrange(type, list, includeHidden = true)
                val hidden = app.folders.hidden(type)
                val m = FolderManage(type, list)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvButton("Mostrar todas", onClick = { app.folders.setAllHidden(type, list, false) })
                    TvButton("Ocultar todas", onClick = { app.folders.setAllHidden(type, list, true) })
                    TvButton("Orden del proveedor", onClick = { app.folders.resetOrder(type) })
                }
                T("${list.size - hidden.count { id -> list.any { it.id == id } }} de ${list.size} visibles", Type.small, p.muted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(all, key = { it.id }) { c ->
                        val isHidden = c.id in hidden
                        ListRow(
                            title = folderLabel(app, type, c),
                            trailing = if (isHidden) "Oculta" else "✓ Visible",
                            onClick = { app.folders.setHidden(type, c.id, !isHidden) },
                            onMenu = { menuFor = c },
                        )
                    }
                }
                menuFor?.let { c -> FolderMenu(app, m, c, onDismiss = { menuFor = null }) }
            }
            LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
        }
    }
}
