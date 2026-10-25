package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.visor.tv.VisorApp
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import kotlinx.coroutines.launch

/** Ventana "Hay una versión nueva": descarga el APK de la release y abre el instalador. */
@Composable
fun UpdateDialog(app: VisorApp) {
    val u = app.updater
    val rel = u.available ?: return
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val downloading = u.progress >= 0f

    // Notas de la release: solo las líneas útiles, sin enlaces ni cabeceras largas
    val notes = rel.notes.lines()
        .map { it.trim().removePrefix("*").removePrefix("-").trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("**Full Changelog") }
        .map { it.replace(Regex("""\s+by @\S+.*$"""), "").replace(Regex("""\s+in https://\S+"""), "") }
        .take(6)

    Dialog(onDismissRequest = { if (!downloading) u.dismiss(rel) }) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("Nueva versión disponible", Type.small, p.muted)
            T("Visor ${rel.version}", Type.heading)
            T("Tienes la ${u.current}", Type.small, p.muted)
            if (notes.isNotEmpty()) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    notes.forEach { T("· $it", Type.small, p.text, maxLines = 2) }
                }
            }
            if (downloading) {
                T("Descargando… ${(u.progress * 100).toInt()} %", Type.small, p.muted)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.line)) {
                    Box(Modifier.fillMaxWidth(u.progress).height(4.dp).background(p.text))
                }
            }
            u.error?.let { T(it, Type.small, p.rec, maxLines = 4) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                TvButton(if (downloading) "Descargando…" else "Actualizar", focusRequester = first, onClick = {
                    if (!downloading) scope.launch { u.downloadAndInstall(rel) }
                })
                if (!downloading) TvButton("Más tarde", onClick = { u.dismiss(rel) })
            }
            T("Se instala encima: conservas la sesión, los favoritos y las grabaciones.", Type.small, p.muted, maxLines = 2)
        }
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}
