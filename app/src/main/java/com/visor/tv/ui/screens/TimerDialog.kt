package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.visor.tv.VisorApp
import com.visor.tv.record.Recording
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Fila cuyo valor se cambia con ◀ ▶ del mando. */
@Composable
fun StepperRow(label: String, value: String, onStep: (Int) -> Unit, focusRequester: FocusRequester? = null) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) p.focusBg else p.surface2)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (focused) p.focusText else p.text
        T(label, Type.label, fg.copy(alpha = 0.75f), Modifier.weight(1f))
        T("◀  $value  ▶", Type.label, fg)
    }
}

private val REPEATS = listOf(
    Recording.REPEAT_ONCE to "Una vez",
    Recording.REPEAT_DAILY to "Diario",
    Recording.REPEAT_WEEKDAYS to "Lun – Vie",
    Recording.REPEAT_WEEKLY to "Semanal",
)

/** Temporizador en su propia ventana (desde ≡ sobre un canal o desde la guía). */
@Composable
fun TimerDialog(
    app: VisorApp,
    streamId: Int,
    channel: String,
    onDismiss: () -> Unit,
    title: String? = null,
    presetStart: Long? = null,
    presetEnd: Long? = null,
) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).heightIn(max = 480.dp).clip(RoundedCornerShape(20.dp)).background(p.surface)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(22.dp)
        ) {
            TimerForm(app, streamId, channel, title, presetStart, presetEnd, onDone = onDismiss, onCancel = onDismiss)
        }
    }
}

/**
 * Formulario de grabación: día, hora de inicio y fin, repetición y dónde guardarla.
 * Con [presetStart]/[presetEnd] (un programa de la guía) llega ya relleno, con los márgenes aplicados.
 */
@Composable
fun TimerForm(
    app: VisorApp,
    streamId: Int,
    channel: String,
    title: String?,
    presetStart: Long?,
    presetEnd: Long?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalPalette.current
    val now = Calendar.getInstance()
    val startCal = Calendar.getInstance().apply {
        timeInMillis = presetStart ?: (now.timeInMillis + 5 * 60_000L)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (presetStart == null) set(Calendar.MINUTE, (get(Calendar.MINUTE) / 5) * 5)
    }
    val today0 = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    var dayOffset by remember { mutableIntStateOf(((startCal.timeInMillis - today0) / 86_400_000L).toInt().coerceIn(0, 13)) }
    var startMin by remember { mutableIntStateOf(startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)) }
    var lengthMin by remember {
        mutableIntStateOf(if (presetStart != null && presetEnd != null) ((presetEnd - presetStart) / 60_000L).toInt().coerceAtLeast(5) else 60)
    }
    var repeatIdx by remember { mutableIntStateOf(0) }
    val volumes = remember { app.recordings.volumes() }
    var volIdx by remember { mutableIntStateOf(volumes.indexOfFirst { it.index == app.settings.storageIndex }.coerceAtLeast(0)) }
    val first = remember { FocusRequester() }

    val dayFmt = SimpleDateFormat("EEEE d MMM", Locale("es"))
    val dayStart = today0 + dayOffset * 86_400_000L
    val startMs = dayStart + startMin * 60_000L
    val endMs = startMs + lengthMin * 60_000L
    fun hhmm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
    fun gb(b: Long) = if (b > 0) " · %.0f GB libres".format(b / 1_073_741_824.0) else ""

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("Programar grabación", Type.h2)
        T(channel + (title?.let { " · $it" } ?: ""), Type.small, p.muted, maxLines = 2)
        StepperRow("Día", dayFmt.format(Date(dayStart)).replaceFirstChar { it.uppercase() }, { dayOffset = (dayOffset + it).coerceIn(0, 13) }, first)
        StepperRow("Empieza", hhmm(startMin), { startMin = (startMin + it * 5 + 1440) % 1440 })
        StepperRow("Termina", hhmm(startMin + lengthMin) + "  (" + lengthMin + " min)", { lengthMin = (lengthMin + it * 5).coerceIn(5, 600) })
        StepperRow("Repetir", REPEATS[repeatIdx].second, { repeatIdx = (repeatIdx + it + REPEATS.size) % REPEATS.size })
        val vol = volumes[volIdx.coerceIn(0, volumes.lastIndex)]
        StepperRow("Guardar en", vol.label + gb(vol.freeBytes), { volIdx = (volIdx + it + volumes.size) % volumes.size })
        T(
            "Al grabar se abre otra conexión con tu proveedor. Si tu cuenta solo permite una, no podrás ver otro canal mientras tanto.",
            Type.small, p.muted, maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Programar", dot = p.rec, onClick = {
                app.recordings.schedule(
                    Recording(
                        streamId = streamId, channel = channel, title = title ?: channel,
                        start = startMs, end = endMs, repeat = REPEATS[repeatIdx].first,
                        source = if (presetStart != null) "epg" else "timer",
                        storage = vol.index,
                    )
                )
                onDone()
            })
            TvButton("Cancelar", onClick = onCancel)
        }
        if (endMs <= System.currentTimeMillis()) T("Esa hora ya ha pasado.", Type.small, Color(0xFFE5484D))
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}
