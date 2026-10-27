package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.Camera
import com.visor.tv.player.CameraVideo
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import kotlinx.coroutines.delay

// ---------------- Mosaico ----------------

/** Todas las cámaras a la vez, sin sonido. OK abre una a pantalla completa; Menú (≡) la edita. */
@Composable
fun CamerasScreen(app: VisorApp, nav: Nav) {
    val p = LocalPalette.current
    val cams = app.cameras.items.toList()
    var editing by remember { mutableStateOf<Camera?>(null) }
    var adding by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<Camera?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("＋ Añadir cámara", onClick = { adding = true })
            if (cams.isNotEmpty()) T("OK: pantalla completa · ≡ Menú: editar, ordenar o borrar", Type.small, p.muted)
        }
        if (cams.isEmpty()) {
            Column(
                Modifier.width(620.dp).clip(RoundedCornerShape(18.dp)).background(p.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("Aún no hay cámaras", Type.h2)
                T(HELP, Type.body, p.muted, maxLines = 12)
            }
        } else {
            // 1 cámara: grande; hasta 4: 2×2; más: 3 por fila
            val cols = when { cams.size == 1 -> 1; cams.size <= 4 -> 2; else -> 3 }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().then(if (cols == 1) Modifier.padding(horizontal = 120.dp) else Modifier),
            ) {
                itemsIndexed(cams, key = { _, c -> c.id }) { i, c ->
                    CameraTile(
                        cam = c,
                        // Escalonado: no abre todas las conexiones en el mismo instante
                        startDelayMs = 400L + i * 150L,
                        onClick = { nav.push(Route.Cam(i)) },
                        onMenu = { actions = c },
                    )
                }
            }
        }
    }

    if (adding) CameraDialog(null, onSave = { app.cameras.upsert(it) }, onDismiss = { adding = false })
    editing?.let { c -> CameraDialog(c, onSave = { app.cameras.upsert(it) }, onDismiss = { editing = null }) }
    actions?.let { c ->
        val idx = cams.indexOfFirst { it.id == c.id }
        ActionsDialog(
            c.name,
            buildList<Pair<String, () -> Unit>> {
                add("Ver a pantalla completa" to { nav.push(Route.Cam(idx.coerceAtLeast(0))) })
                add("Editar" to { editing = c })
                if (idx > 0) add("Mover antes" to { app.cameras.move(c.id, -1) })
                if (idx < cams.lastIndex) add("Mover después" to { app.cameras.move(c.id, 1) })
                add("Borrar" to { app.cameras.remove(c.id) })
                add("Cerrar" to {})
            },
            onDismiss = { actions = null },
        )
    }
}

@Composable
private fun CameraTile(cam: Camera, startDelayMs: Long, onClick: () -> Unit, onMenu: () -> Unit) {
    val p = LocalPalette.current
    Focusable(onClick = onClick, onMenu = onMenu, shape = RoundedCornerShape(14.dp)) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black),
        ) {
            CameraVideo(cam.mosaicUrl, Modifier.fillMaxSize(), muted = true, startDelayMs = startDelayMs)
            Box(
                Modifier.align(Alignment.BottomStart).padding(10.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { T(cam.name, Type.label, Color.White) }
            // El borde va encima del vídeo para que se vea qué cámara está seleccionada
            Box(
                Modifier.fillMaxSize().border(
                    if (focused) 4.dp else 1.dp,
                    if (focused) p.focusBg else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(14.dp),
                )
            )
        }
    }
}

// ---------------- Pantalla completa ----------------

/** Una cámara a pantalla completa con sonido. ← → cambia de cámara, Atrás vuelve al mosaico. */
@Composable
fun CameraFullScreen(app: VisorApp, start: Int) {
    val cams = app.cameras.items.toList()
    if (cams.isEmpty()) return
    var index by remember { mutableStateOf(start.coerceIn(0, cams.lastIndex)) }
    val cam = cams[index.coerceIn(0, cams.lastIndex)]
    var showInfo by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    // El nombre se muestra unos segundos al entrar o al cambiar de cámara
    LaunchedEffect(index) { showInfo = true; delay(4_000); showInfo = false }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.DirectionDown, Key.ChannelDown, Key.MediaNext -> { index = (index + 1) % cams.size; true }
                    Key.DirectionLeft, Key.DirectionUp, Key.ChannelUp, Key.MediaPrevious -> { index = (index - 1 + cams.size) % cams.size; true }
                    Key.DirectionCenter, Key.Enter -> { showInfo = !showInfo; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        CameraVideo(cam.url, Modifier.fillMaxSize(), muted = false, textureView = false)
        if (showInfo) {
            Column(
                Modifier.align(Alignment.TopStart).padding(32.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0x99000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                T(cam.name, Type.heading, Color.White)
                if (cams.size > 1) T("${index + 1} de ${cams.size} · ← → cambiar de cámara · Atrás: mosaico", Type.small, Color.White.copy(alpha = 0.8f))
                else T("Atrás: volver", Type.small, Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

// ---------------- Añadir / editar ----------------

private const val HELP =
    "Añade cada cámara con un nombre y la dirección de su vídeo (rtsp://…).\n\n" +
        "Cámaras Xiaomi: la forma más fiable es tener go2rtc en un equipo de casa siempre encendido " +
        "(Home Assistant, un NAS, una Raspberry o un PC). go2rtc entra con tu cuenta de Mi Home y ofrece cada cámara " +
        "en la red como rtsp://IP-del-equipo:8554/nombre. Esa es la dirección que va aquí.\n\n" +
        "Si la cámara tiene RTSP propio (o un firmware que lo añade), pon directamente su dirección, " +
        "por ejemplo rtsp://usuario:contraseña@192.168.1.50:554/stream."

@Composable
private fun CameraDialog(existing: Camera?, onSave: (Camera) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "rtsp://") }
    var gridUrl by remember { mutableStateOf(existing?.gridUrl ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit, uri: Boolean, fr: FocusRequester? = null) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text, autoCorrectEnabled = false),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = p.text, unfocusedBorderColor = p.line,
                focusedLabelColor = p.text, unfocusedLabelColor = p.muted, cursorColor = p.text,
            ),
            modifier = Modifier.fillMaxWidth().then(if (fr != null) Modifier.focusRequester(fr) else Modifier),
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(20.dp)).background(p.surface)
                .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(if (existing == null) "Añadir cámara" else "Editar cámara", Type.h2)
            field("Nombre (p. ej. Habitación de Lucía)", name, { name = it }, uri = false, fr = first)
            field("Dirección del vídeo (rtsp://…)", url, { url = it.trim() }, uri = true)
            field("Dirección para el mosaico, en baja calidad (opcional)", gridUrl, { gridUrl = it.trim() }, uri = true)
            T("Se guardan cifradas en este aparato. Con go2rtc: rtsp://IP-del-equipo:8554/nombre", Type.small, p.muted, maxLines = 2)
            error?.let { T(it, Type.small, p.rec, maxLines = 2) }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Guardar", onClick = {
                    val ok = Regex("^(rtsp|rtsps|http|https|rtmp)://.+", RegexOption.IGNORE_CASE)
                    when {
                        name.isBlank() -> error = "Ponle un nombre a la cámara."
                        !ok.matches(url) -> error = "La dirección debe empezar por rtsp://, http:// o https://"
                        gridUrl.isNotBlank() && !ok.matches(gridUrl) -> error = "La dirección del mosaico no es válida."
                        else -> {
                            val c = existing?.copy(name = name.trim(), url = url, gridUrl = gridUrl)
                                ?: Camera(name = name.trim(), url = url, gridUrl = gridUrl)
                            onSave(c)
                            onDismiss()
                        }
                    }
                })
                TvButton("Cancelar", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}
