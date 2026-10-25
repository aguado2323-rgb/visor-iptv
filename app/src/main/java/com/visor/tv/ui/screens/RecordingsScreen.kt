package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visor.tv.VisorApp
import com.visor.tv.data.FavItem
import com.visor.tv.data.PlayItem
import com.visor.tv.play
import com.visor.tv.record.Recording
import com.visor.tv.ui.Empty
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> ""
}

private fun kindLabel(r: Recording) = when (r.source) { "epg" -> "EPG"; "manual" -> "REC"; else -> "Temporizador" } +
    when (r.repeat) { Recording.REPEAT_DAILY -> " · diario"; Recording.REPEAT_WEEKDAYS -> " · L-V"; Recording.REPEAT_WEEKLY -> " · semanal"; else -> "" }

@Composable
fun RecordingsScreen(app: VisorApp) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var action by remember { mutableStateOf<Recording?>(null) }
    var pickChannel by remember { mutableStateOf(false) }
    var timerFor by remember { mutableStateOf<FavItem?>(null) }

    val all = app.recordings.items.toList()
    val now = all.filter { it.status == Recording.STATUS_RECORDING }
    val next = all.filter { it.status == Recording.STATUS_SCHEDULED }.sortedBy { it.start }
    val done = all.filter { it.status in listOf(Recording.STATUS_DONE, Recording.STATUS_FAILED, Recording.STATUS_CANCELLED) }.sortedByDescending { it.start }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            fun section(title: String, list: List<Recording>) {
                item { T(title, Type.small, p.muted, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)) }
                if (list.isEmpty()) item { T("Nada por aquí", Type.small, p.muted, Modifier.padding(start = 12.dp)) }
                items(list, key = { it.id }) { r ->
                    val sub = when (r.status) {
                        Recording.STATUS_RECORDING -> "${r.channel} · hasta ${hm(r.end)} · ${size(r.bytes)}"
                        Recording.STATUS_SCHEDULED -> "${r.channel} · ${dayHm(r.start)} – ${hm(r.end)}"
                        Recording.STATUS_DONE -> "${r.channel} · ${dayHm(r.start)} · ${size(r.bytes)}"
                        Recording.STATUS_FAILED -> "Falló: ${r.error}"
                        else -> "Cancelada"
                    }
                    ListRow(
                        title = r.title,
                        subtitle = sub,
                        trailing = kindLabel(r),
                        dot = if (r.status == Recording.STATUS_RECORDING) p.rec else null,
                        onClick = { action = r },
                        onMenu = { action = r },
                    )
                }
            }
            section("Grabando ahora", now)
            section("Programadas", next)
            section("Grabadas", done)
        }
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(18.dp)).background(p.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("Nuevo temporizador", Type.h2)
            T("Graba un canal a la hora que tú marques, una vez o de forma periódica. También puedes grabar desde la Guía o con ≡ sobre un canal.", Type.small, p.muted, maxLines = 5)
            TvButton("Programar grabación…", dot = p.rec, onClick = { pickChannel = true }, modifier = Modifier.fillMaxWidth())
            val vol = app.recordings.volumes()
            val v = vol.firstOrNull { it.index == app.settings.storageIndex } ?: vol.first()
            T("Se guarda en: ${v.label}" + (size(v.freeBytes).takeIf { it.isNotBlank() }?.let { " · $it libres" } ?: ""), Type.small, p.muted, maxLines = 2)
            T("Se cambia en Ajustes. Conecta una memoria USB si el espacio es poco.", Type.small, p.muted, maxLines = 2)
        }
    }

    action?.let { r ->
        val acts = buildList<Pair<String, () -> Unit>> {
            when (r.status) {
                Recording.STATUS_RECORDING -> add("Detener grabación" to { app.recordings.cancel(r.id) })
                Recording.STATUS_SCHEDULED -> add("Cancelar grabación" to { app.recordings.cancel(r.id) })
                Recording.STATUS_DONE -> {
                    add("Reproducir" to {
                        play(ctx, listOf(PlayItem(kind = "file", id = r.id.hashCode(), title = r.title, subtitle = r.channel, filePath = r.file)), 0)
                    })
                    add("Borrar" to { app.recordings.remove(r.id) })
                }
                else -> add("Quitar de la lista" to { app.recordings.remove(r.id) })
            }
            add("Cerrar" to {})
        }
        ActionsDialog(r.title, acts, onDismiss = { action = null }, subtitle = r.channel)
    }

    if (pickChannel) {
        val favs = app.favorites.of("live")
        if (favs.isEmpty()) {
            ActionsDialog(
                "Elige el canal",
                listOf("Entendido" to {}),
                onDismiss = { pickChannel = false },
                subtitle = "Primero añade el canal a favoritos (≡ sobre el canal en TV), o prográmalo directamente desde allí.",
            )
        } else {
            ActionsDialog(
                "Elige el canal",
                favs.map { f -> f.name to { timerFor = f } },
                onDismiss = { pickChannel = false },
            )
        }
    }
    timerFor?.let { f -> TimerDialog(app, f.id, f.name, onDismiss = { timerFor = null }) }
}
