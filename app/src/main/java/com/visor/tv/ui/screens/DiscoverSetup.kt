package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visor.tv.Nav
import com.visor.tv.VisorApp
import com.visor.tv.addons.InstalledAddon
import com.visor.tv.addons.PairServer
import com.visor.tv.data.next
import com.visor.tv.debrid.DebridAccount
import com.visor.tv.debrid.DebridKind
import com.visor.tv.debrid.DeviceCode
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Qué diálogo está abierto en la pantalla de ajustes de Descubrir. */
private sealed interface SetupDialog {
    data class AddonMenu(val addon: InstalledAddon) : SetupDialog
    data class Account(val kind: DebridKind) : SetupDialog
    data class Device(val kind: DebridKind) : SetupDialog
    data class TypeIn(val kind: DebridKind?) : SetupDialog
    data object Phone : SetupDialog
}

private fun dateOf(ms: Long) = SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(ms))

private fun DebridAccount.status(): String = buildString {
    if (user.isNotBlank()) append(user).append(" · ")
    append(
        when {
            premiumUntil > 0 && premiumUntil < System.currentTimeMillis() -> "premium caducado"
            premium && premiumUntil > 0 -> "premium hasta el ${dateOf(premiumUntil)}"
            premium -> "premium"
            else -> "sin premium: no podrá abrir fuentes"
        }
    )
}

/** Addons, cuentas debrid y preferencias de fuentes de la sección Descubrir. */
@Composable
fun DiscoverSetupScreen(app: VisorApp, nav: Nav) {
    val p = LocalPalette.current
    val d = app.discover
    val s = app.settings
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SetupDialog?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    // Las cuentas y las direcciones de addons son tan sensibles como la del proveedor: mismo PIN que Ajustes
    if (app.vault.hasPin() && !nav.settingsUnlocked) {
        PinPad("Descubrir: addons y cuentas", subtitle = "Protegido con el PIN de Ajustes", onDone = { pin ->
            if (app.vault.checkPin(pin)) { nav.settingsUnlocked = true; true } else false
        }, onCancel = { nav.pop() })
        return
    }

    LaunchedEffect(Unit) { d.refreshAccounts() }

    @Composable
    fun section(title: String, top: Boolean = false) =
        T(title, Type.small, p.muted, Modifier.padding(start = 12.dp, top = if (top) 4.dp else 14.dp, bottom = 4.dp))

    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp)) {
        T("Addons y debrid", Type.heading)
        T(
            "Los addons dan los catálogos y las fuentes de Descubrir. Las fuentes de torrent necesitan una cuenta debrid; Visor solo abre lo que ya está en su caché.",
            Type.small, p.muted, Modifier.padding(top = 4.dp, bottom = 12.dp), maxLines = 2,
        )
        LazyColumn(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item { section("Addons instalados · el orden manda en las filas y en las fichas", top = true) }
            items(d.addons.toList(), key = { "a:" + it.key }) { a ->
                ListRow(
                    a.manifest.name.ifBlank { a.host },
                    subtitle = a.manifest.summary + " · " + a.host,
                    trailing = if (a.enabled) "Activo" else "Desactivado",
                    dot = if (a.enabled) androidx.compose.ui.graphics.Color(0xFF3DBE7A) else p.line,
                    onClick = { dialog = SetupDialog.AddonMenu(a) },
                    onMenu = { dialog = SetupDialog.AddonMenu(a) },
                )
            }
            if (!d.hasStreamAddon) item {
                T(
                    "Aún no hay ningún addon de fuentes: verás catálogos y fichas, pero no podrás reproducir. Añade el addon que ya usas en Stremio o Nuvio.",
                    Type.small, p.rec, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 3,
                )
            }
            item { ListRow("Añadir desde el móvil o el PC", subtitle = "Abre una página en el navegador y pega la dirección del addon (la que termina en manifest.json)", onClick = { dialog = SetupDialog.Phone }) }
            item { ListRow("Añadir escribiendo la dirección", subtitle = "Con el teclado del mando", onClick = { dialog = SetupDialog.TypeIn(null) }) }

            item { section("Cuentas debrid") }
            items(DebridKind.entries.toList(), key = { "d:" + it.name }) { k ->
                val acc = d.accounts.firstOrNull { it.kind == k }
                ListRow(
                    k.label,
                    subtitle = acc?.status() ?: (if (d.service(k).deviceCode) "Conectar con un código, sin escribir la clave" else "Conectar con la clave de tu cuenta (API key)"),
                    trailing = if (acc != null) "Conectada" else "Conectar",
                    dot = if (acc != null) (if (acc.premium) androidx.compose.ui.graphics.Color(0xFF3DBE7A) else p.rec) else null,
                    onClick = {
                        dialog = when {
                            acc != null -> SetupDialog.Account(k)
                            d.service(k).deviceCode -> SetupDialog.Device(k)
                            else -> SetupDialog.Account(k)
                        }
                    },
                )
            }

            item { section("Copia de seguridad") }
            item {
                ListRow(
                    "Copia de addons y cuentas",
                    subtitle = "Descárgala o restáurala desde el navegador del PC o del móvil. Lleva tus claves: guárdala en un sitio seguro",
                    onClick = { dialog = SetupDialog.Phone },
                )
            }

            item { section("Fuentes") }
            item { ListRow("Elegir la fuente", subtitle = "Automática: Visor elige la mejor y, si falla, prueba la siguiente", trailing = if (s.autoSource) "Automática" else "Preguntar siempre", onClick = { s.updateAutoSource(!s.autoSource) }) }
            item { ListRow("Calidad máxima", subtitle = "Útil si la conexión o la tele no dan para 4K", trailing = s.maxQuality.label, onClick = { s.updateMaxQuality(s.maxQuality.next()) }) }
            item { ListRow("Tamaño máximo", subtitle = "Los archivos más grandes necesitan más velocidad", trailing = s.maxSize.label, onClick = { s.updateMaxSize(s.maxSize.next()) }) }
            item { ListRow("Español primero", subtitle = "Sube las fuentes con audio en castellano o latino", trailing = if (s.spanishFirst) "Sí" else "No", onClick = { s.updateSpanishFirst(!s.spanishFirst) }) }
            item { ListRow("Ocultar grabaciones de cine", subtitle = "CAM, TS y similares", trailing = if (s.hideCam) "Sí" else "No", onClick = { s.updateHideCam(!s.hideCam) }) }
            if (msg != null) item { T(msg!!, Type.small, p.muted, Modifier.padding(12.dp), maxLines = 3) }
            item {
                T(
                    "Visor no incluye ni recomienda fuentes. Tú eliges qué addons instalar y eres responsable de que su contenido sea legal donde vives. Las direcciones y claves se guardan cifradas en este aparato y no entran en la copia de seguridad.",
                    Type.small, p.muted, Modifier.padding(start = 12.dp, top = 18.dp, bottom = 32.dp), maxLines = 4,
                )
            }
        }
    }

    when (val dg = dialog) {
        null -> {}
        is SetupDialog.AddonMenu -> {
            val a = dg.addon
            val i = d.addons.indexOfFirst { it.key == a.key }
            ActionsDialog(
                a.manifest.name.ifBlank { a.host },
                listOfNotNull(
                    (if (a.enabled) "Desactivar" else "Activar") to { d.setEnabled(a, !a.enabled) },
                    if (i > 0) "Subir" to { d.move(a, -1) } else null,
                    if (i in 0 until d.addons.lastIndex) "Bajar" to { d.move(a, 1) } else null,
                    "Actualizar" to {
                        scope.launch {
                            msg = "Actualizando ${a.manifest.name}…"
                            msg = try { d.install(a.base + "/manifest.json"); "${a.manifest.name} actualizado" } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "No se pudo actualizar" }
                        }
                        Unit
                    },
                    "Quitar" to { d.remove(a); msg = "${a.manifest.name} quitado" },
                    "Cancelar" to {},
                ),
                onDismiss = { dialog = null },
                subtitle = a.manifest.description.ifBlank { a.manifest.summary },
            )
        }
        is SetupDialog.Account -> {
            val k = dg.kind
            val acc = d.account(k)
            ActionsDialog(
                k.label,
                if (acc != null) listOf(
                    "Comprobar la cuenta" to {
                        scope.launch {
                            msg = try { val svc = d.service(k); d.replaceAccount(svc.check(svc.fresh(acc))); "${k.label}: ${d.account(k)?.status()}" } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "No se pudo comprobar" }
                        }
                        Unit
                    },
                    "Desconectar" to { d.disconnect(k); msg = "${k.label} desconectada" },
                    "Cancelar" to {},
                ) else listOfNotNull(
                    if (d.service(k).deviceCode) "Conectar con un código" to { dialog = SetupDialog.Device(k) } else null,
                    "Pegar la clave desde el móvil" to { dialog = SetupDialog.Phone },
                    "Escribir la clave" to { dialog = SetupDialog.TypeIn(k) },
                    "Cancelar" to {},
                ),
                // ActionsDialog cierra al elegir: solo se limpia si la acción no abrió otro diálogo
                onDismiss = { if (dialog == dg) dialog = null },
                subtitle = acc?.status() ?: "La clave está en ${k.site}, en el apartado de tu cuenta (API key)",
            )
        }
        is SetupDialog.Device -> DeviceCodeDialog(app, dg.kind, onDone = { acc ->
            dialog = null
            msg = "${acc.kind.label} conectada: ${acc.status()}"
        }, onDismiss = { dialog = null })
        is SetupDialog.TypeIn -> TypeInDialog(app, dg.kind, onDone = { m -> dialog = null; msg = m }, onDismiss = { dialog = null })
        SetupDialog.Phone -> PhonePairDialog(app, onDismiss = { dialog = null })
    }
}

/** Marco común de los diálogos de esta pantalla. */
@Composable
private fun SetupFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(title, Type.h2, maxLines = 2)
            content()
        }
    }
}

/** Texto grande y legible desde el sofá (códigos y direcciones). */
@Composable
private fun BigText(text: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bg).padding(vertical = 14.dp, horizontal = 16.dp)) {
        T(text, Type.title.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp), maxLines = 2)
    }
}

/** Conectar Real-Debrid o AllDebrid con un código: el usuario lo escribe en la web del servicio desde el móvil. */
@Composable
private fun DeviceCodeDialog(app: VisorApp, kind: DebridKind, onDone: (DebridAccount) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val d = app.discover
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        error = null
        code = null
        val svc = d.service(kind)
        try {
            val c = svc.startDevice()
            code = c
            while (System.currentTimeMillis() < c.expiresAt) {
                delay(c.intervalSec * 1000L)
                val acc = try { svc.pollDevice(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (acc != null) { d.replaceAccount(acc); onDone(acc); return@LaunchedEffect }
            }
            error = "El código ha caducado."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "No se pudo pedir el código"
        }
    }

    SetupFrame("Conectar ${kind.label}", onDismiss) {
        val c = code
        when {
            error != null -> T(error!!, Type.body, p.rec, maxLines = 3)
            c == null -> T("Pidiendo un código…", Type.body, p.muted)
            else -> {
                T("1. En el móvil o el ordenador, abre:", Type.body, p.muted)
                BigText(c.verifyUrl.removePrefix("https://"))
                T("2. Escribe este código:", Type.body, p.muted)
                BigText(c.userCode)
                T("Esperando a que lo autorices… se conecta solo.", Type.small, p.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (error != null) TvButton("Pedir otro código", onClick = { attempt++ })
            TvButton("Cancelar", onClick = onDismiss, focusRequester = first)
        }
    }
    LaunchedEffect(Unit) { delay(50); try { first.requestFocus() } catch (_: Exception) { } }
}

/** Escribir con el mando la dirección de un addon ([kind] null) o la clave de un debrid. */
@Composable
private fun TypeInDialog(app: VisorApp, kind: DebridKind?, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val d = app.discover
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(if (kind == null) "https://" else "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }

    fun submit() {
        if (busy || text.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                val m = if (kind == null) d.install(text).let { "${it.manifest.name} añadido" }
                else d.connectWithKey(kind, text).let { "${kind.label} conectada: ${it.status()}" }
                onDone(m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo"
            } finally {
                busy = false
            }
        }
    }

    SetupFrame(if (kind == null) "Añadir addon" else "Clave de ${kind.label}", onDismiss) {
        T(
            if (kind == null) "La dirección que te da la web del addon al configurarlo. Vale con o sin /manifest.json, y también stremio://."
            else "Está en ${kind.site}, en el apartado de tu cuenta (API key).",
            Type.small, p.muted, maxLines = 3,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(if (kind == null) "Dirección del addon" else "Clave") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (kind == null) KeyboardType.Uri else KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = p.text, unfocusedBorderColor = p.line,
                focusedLabelColor = p.text, unfocusedLabelColor = p.muted, cursorColor = p.text,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        if (error != null) T(error!!, Type.small, p.rec, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            TvButton(if (busy) "Comprobando…" else if (kind == null) "Añadir" else "Conectar", onClick = { submit() })
            TvButton("Cancelar", onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { delay(50); try { field.requestFocus() } catch (_: Exception) { } }
}

/** Página en la red de casa para pegar direcciones y claves desde el móvil. */
@Composable
private fun PhonePairDialog(app: VisorApp, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val d = app.discover
    var received by remember { mutableStateOf<List<String>>(emptyList()) }
    var locked by remember { mutableStateOf(false) }
    val server = remember {
        PairServer(
            listOf("addon" to "Dirección de un addon") + DebridKind.entries.map { it.name to "Clave de ${it.label}" },
            onSubmit = { what, value ->
                val m = when (what) {
                    "addon" -> d.install(value).let { "${it.manifest.name} añadido" }
                    PairServer.RESTORE -> d.restoreBackup(value)
                    else -> DebridKind.valueOf(what).let { k -> d.connectWithKey(k, value); "${k.label} conectada" }
                }
                received = received + m
                m
            },
            backup = { d.exportBackup() },
        )
    }
    val address = remember { server.start() }
    DisposableEffect(server) {
        server.onLocked = { locked = true }
        onDispose { server.stop() }
    }
    val first = remember { FocusRequester() }

    SetupFrame("Desde el móvil o el PC", onDismiss) {
        if (address == null) {
            T("La tele no parece estar conectada a una red local. Usa la opción de escribir la dirección.", Type.body, p.rec, maxLines = 3)
        } else {
            T("Con el móvil o el PC en la misma red, abre en el navegador:", Type.body, p.muted)
            BigText(address.removePrefix("http://"))
            T("y escribe este código:", Type.body, p.muted)
            BigText(server.code)
            T("Ahí puedes añadir addons y claves, y descargar o restaurar la copia de seguridad de Descubrir. Úsalo solo en la red de tu casa; la página se cierra al salir de aquí.", Type.small, p.muted, maxLines = 3)
            if (locked) T("Demasiados códigos equivocados: la página se ha cerrado.", Type.small, p.rec)
            received.forEach { T("✓ $it", Type.label) }
        }
        Row(Modifier.padding(top = 6.dp)) { TvButton(if (received.isEmpty()) "Cerrar" else "Hecho", onClick = onDismiss, focusRequester = first) }
    }
    LaunchedEffect(Unit) { delay(50); try { first.requestFocus() } catch (_: Exception) { } }
}
