package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visor.tv.VisorApp
import com.visor.tv.data.EpgEntry
import com.visor.tv.data.LiveStream
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Img
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Lo elegido en el paso "¿Qué grabar?": un programa de la guía o nada (horas a mano). */
private data class TimerPick(val title: String?, val start: Long?, val end: Long?)

/**
 * Nuevo temporizador en tres pasos:
 *  1. cualquier canal de la lista (por carpetas, favoritos o buscando),
 *  2. un programa de su guía o "elegir horas a mano",
 *  3. día, horas, repetición y dónde guardarla.
 * Atrás vuelve al paso anterior. Con [presetChannel] (desde ≡ sobre un canal) empieza en el paso 2.
 */
@Composable
fun NewTimerDialog(app: VisorApp, onDismiss: () -> Unit, presetChannel: LiveStream? = null) {
    val p = LocalPalette.current
    var channel by remember { mutableStateOf(presetChannel) }
    var pick by remember { mutableStateOf<TimerPick?>(null) }

    fun back() {
        when {
            pick != null -> pick = null
            channel != null && presetChannel == null -> channel = null
            else -> onDismiss()
        }
    }

    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val ch = channel
        val pk = pick
        Column(
            Modifier
                .width(if (ch == null) 860.dp else 600.dp)
                .height(470.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.surface)
                .padding(22.dp),
        ) {
            when {
                ch == null -> ChannelStep(app) { channel = it }
                pk == null -> ProgramStep(app, ch) { pick = it }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TimerForm(
                        app, ch.id, ch.name, pk.title, pk.start, pk.end,
                        onDone = onDismiss, onCancel = { back() },
                    )
                }
            }
        }
    }
}

/** Paso 1: elegir cualquier canal. */
@Composable
private fun ChannelStep(app: VisorApp, onPick: (LiveStream) -> Unit) {
    val p = LocalPalette.current
    val cats = load(app.repo.version) { app.repo.liveCategories() }
    var selected by remember { mutableStateOf(FAV_FOLDER) }
    val entry = remember { FolderEntry() }
    // Al abrir, el foco empieza en la carpeta de Favoritos
    LaunchedEffect(entry.attached) {
        if (entry.attached) try { entry.fr.requestFocus() } catch (_: Exception) { }
    }

    Column(Modifier.fillMaxSize()) {
        T("Nuevo temporizador · elige el canal", Type.h2)
        T("Cualquier canal de tu lista. Atrás para cerrar.", Type.small, p.muted, Modifier.padding(bottom = 10.dp))
        Row(Modifier.fillMaxSize().enterAtFolders(entry), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LoadView(cats) { list ->
                FolderColumn(fixedFolders(withNew = false) + arrangedFolders(app, "live", list), selected, { selected = it }, width = 230.dp, entry = entry)
            }
            Column(Modifier.weight(1f)) {
                if (selected == SEARCH_FOLDER) {
                    SearchPanel("Buscar canal", { val ex = app.folders.excludes("live"); app.repo.allLive().filterNot { ex(it.categoryId) } }, { it.name }) { found ->
                        PickChannelList(found, onPick)
                    }
                } else if (isBlockedFolder(app, "live", selected)) {
                    LockedPanel(app, "live", selected)
                } else {
                    val streams = load(selected, app.repo.version) {
                        kotlinx.coroutines.delay(FOLDER_DEBOUNCE_MS) // al recorrer carpetas deprisa no se pide cada una por el camino
                        if (selected == FAV_FOLDER) app.favorites.of("live").map { it.toLive() } else app.repo.liveStreams(selected)
                    }
                    LoadView(streams) { list ->
                        if (list.isEmpty()) {
                            Empty(if (selected == FAV_FOLDER) "Sin canales favoritos. Elige una carpeta o busca el canal." else "Esta carpeta está vacía.")
                        } else {
                            PickChannelList(list, onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickChannelList(list: List<LiveStream>, onPick: (LiveStream) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(list, key = { it.id }) { s ->
            ListRow(
                title = (if (s.num > 0) "${s.num}  " else "") + s.name,
                leading = { Img(s.icon, Modifier.size(width = 40.dp, height = 27.dp).clip(RoundedCornerShape(6.dp)), crop = false) },
                onClick = { onPick(s) },
            )
        }
    }
}

/** Paso 2: un programa de la guía del canal, o "elegir horas a mano". */
@Composable
private fun ProgramStep(app: VisorApp, ch: LiveStream, onPick: (TimerPick) -> Unit) {
    val p = LocalPalette.current
    val first = remember { FocusRequester() }
    val epg = load(ch.id) {
        val now = System.currentTimeMillis()
        app.repo.shortEpg(ch.id, 60).filter { it.end > now }.sortedBy { it.start }
    }
    val before = app.settings.marginBefore * 60_000L
    val after = app.settings.marginAfter * 60_000L
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val dayFmt = SimpleDateFormat("EEE d", Locale("es"))
    fun when_(e: EpgEntry): String {
        val c = Calendar.getInstance().apply { timeInMillis = e.start }
        val day = when (c.get(Calendar.DAY_OF_YEAR) - today) {
            0 -> ""
            1 -> "Mañana "
            else -> dayFmt.format(Date(e.start)).replaceFirstChar { it.uppercase() } + " "
        }
        return day + hm(e.start) + " – " + hm(e.end)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(ch.name, Type.h2, maxLines = 1)
        T("¿Qué quieres grabar? Atrás para cambiar de canal.", Type.small, p.muted, Modifier.padding(bottom = 6.dp))
        MenuRow("◷  Elegir día y horas a mano", focusRequester = first) { onPick(TimerPick(null, null, null)) }
        T("Programas de la guía", Type.small, p.muted, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
        LoadView(epg) { list ->
            if (list.isEmpty()) {
                Empty("Este canal no tiene guía. Elige las horas a mano.")
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(list, key = { it.start }) { e ->
                        val live = e.isNow()
                        ListRow(
                            title = e.title.ifBlank { "Sin título" },
                            subtitle = (if (live) "Ahora · hasta ${hm(e.end)}" else when_(e)) +
                                (e.desc.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            dot = if (live) p.rec else null,
                            onClick = { onPick(TimerPick(e.title, e.start - before, e.end + after)) },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}
