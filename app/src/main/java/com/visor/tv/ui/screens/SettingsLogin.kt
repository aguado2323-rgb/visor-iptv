package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.visor.tv.Nav
import com.visor.tv.VisorApp
import com.visor.tv.data.Account
import com.visor.tv.data.AccountInfo
import com.visor.tv.data.ArtSource
import com.visor.tv.data.next
import com.visor.tv.ui.ListRow
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Inicio de sesión ----------------

@Composable
fun LoginScreen(app: VisorApp) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("https://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = p.text, unfocusedBorderColor = p.line,
                focusedLabelColor = p.text, unfocusedLabelColor = p.muted, cursorColor = p.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Box(Modifier.fillMaxSize().background(p.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Visor", Type.title)
            T("Introduce los datos Xtream de tu proveedor, o pega en Servidor el enlace M3U completo y se rellenan solos. Se guardan cifrados en este aparato.", Type.small, p.muted, maxLines = 3)
            field("Servidor o enlace M3U", server, { v ->
                val t = v.trim()
                val parsed = parseXtreamLink(t)
                if (parsed != null) { server = parsed.server; user = parsed.user; pass = parsed.pass } else server = t
            })
            field("Usuario", user, { user = it.trim() })
            field("Contraseña", pass, { pass = it }, password = true)
            if (error != null) T(error!!, Type.small, p.rec, maxLines = 3)
            TvButton(if (busy) "Comprobando…" else "Entrar", focusRequester = first, onClick = {
                if (busy) return@TvButton
                if (!server.startsWith("http")) { error = "El servidor debe empezar por http:// o https://"; return@TvButton }
                if (!server.startsWith("https://")) error = "Aviso: sin https, la contraseña viaja sin cifrar."
                busy = true
                scope.launch {
                    try {
                        app.repo.login(Account(baseOf(server), user, pass))
                    } catch (e: Exception) {
                        error = "No se pudo entrar: ${e.message ?: "error de conexión"}"
                    } finally {
                        busy = false
                    }
                }
            })
        }
    }
}

// ---------------- PIN ----------------

/** Teclado numérico para el mando. onDone devuelve true si el PIN es válido. */
@Composable
fun PinPad(title: String, onDone: (String) -> Boolean, subtitle: String? = null, onCancel: (() -> Unit)? = null) {
    val p = LocalPalette.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(title, Type.heading)
            if (subtitle != null) T(subtitle, Type.small, p.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) p.text else p.line))
                }
            }
            if (wrong) T("PIN incorrecto", Type.small, p.rec)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            keys.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, k ->
                        TvButton(k, modifier = Modifier.width(64.dp), focusRequester = if (r == 0 && c == 0) first else null, onClick = {
                            wrong = false
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> if (pin.length == 4) { if (!onDone(pin)) { wrong = true; pin = "" } }
                                else -> if (pin.length < 4) {
                                    pin += k
                                    if (pin.length == 4 && !onDone(pin)) { wrong = true; pin = "" }
                                }
                            }
                        })
                    }
                }
            }
            if (onCancel != null) TvButton("Cancelar", onClick = onCancel)
        }
    }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }
}

// ---------------- Ajustes ----------------

@Composable
fun SettingsScreen(app: VisorApp, nav: Nav) {
    val p = LocalPalette.current
    val s = app.settings
    val scope = rememberCoroutineScope()
    var pinMode by remember { mutableStateOf<String?>(null) } // "new"
    var confirmLogout by remember { mutableStateOf(false) }
    val backup = remember { com.visor.tv.data.Backup(app) }
    var toRestore by remember { mutableStateOf<com.visor.tv.data.BackupData?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var foldersOpen by remember { mutableStateOf(false) }
    if (foldersOpen) FolderManagerDialog(app) { foldersOpen = false }

    if (app.vault.hasPin() && !nav.settingsUnlocked) {
        PinPad("Ajustes protegidos", onDone = { pin ->
            if (app.vault.checkPin(pin)) { nav.settingsUnlocked = true; true } else false
        })
        return
    }
    if (pinMode == "new") {
        PinPad("Nuevo PIN de 4 cifras", subtitle = "Protege Ajustes y, si quieres, la apertura de la app", onDone = { pin ->
            app.vault.setPin(pin); nav.settingsUnlocked = true; pinMode = null; msg = "PIN guardado"; true
        }, onCancel = { pinMode = null })
        return
    }

    val acct by produceState<AccountInfo?>(null) { value = try { app.repo.accountInfo() } catch (e: Exception) { null } }
    val vols = app.recordings.volumes()
    val vol = vols.firstOrNull { it.index == s.storageIndex } ?: vols.first()
    val host = app.repo.account?.base?.substringAfter("://")?.substringBefore(":")?.let { h ->
        if (h.length > 6) h.take(3) + "•••" + h.takeLast(4) else "•••"
    } ?: ""

    LazyColumn(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { T("Aspecto y reproducción", Type.small, p.muted, Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) }
        item { ListRow("Tema", trailing = s.theme.label, onClick = { s.updateTheme(s.theme.next()) }) }
        item { ListRow("Reproductor de TV en directo", subtitle = "Si un canal no va con uno, prueba con el otro", trailing = s.engine.label, onClick = { s.updateEngine(s.engine.next()) }) }
        item { ListRow("Reproductor de películas y series", subtitle = "También para las grabaciones", trailing = s.vodEngine.label, onClick = { s.updateVodEngine(s.vodEngine.next()) }) }
        item { ListRow("Formato de imagen", subtitle = "Ajustar con bandas, rellenar estirando o zoom recortando. También en el reproductor", trailing = s.aspect.label, onClick = { s.updateAspect(s.aspect.next()) }) }
        item { ListRow("Formato de los canales en directo", trailing = s.liveFormat.label, onClick = { s.updateLiveFormat(s.liveFormat.next()) }) }
        item { ListRow("Idioma de audio preferido", trailing = s.audioLang.label, onClick = { s.updateAudio(s.audioLang.next()) }) }
        item { ListRow("Subtítulos al empezar", trailing = s.subsDefault.label, onClick = { s.updateSubs(s.subsDefault.next()) }) }
        item {
            ListRow(
                "Carátulas y sinopsis",
                subtitle = if (app.tmdb.available) "Las del proveedor o las de TMDB (en fichas)" else "TMDB no está incluido en esta compilación",
                trailing = if (app.tmdb.available) s.artSource.label else ArtSource.PROVIDER.label,
                onClick = { if (app.tmdb.available) s.updateArt(s.artSource.next()) },
            )
        }
        item { T("Grabaciones", Type.small, p.muted, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
        item {
            ListRow("Guardar en", subtitle = "Pulsa para cambiar entre los almacenamientos detectados", trailing = vol.label, onClick = {
                val i = vols.indexOfFirst { it.index == vol.index }
                s.updateStorage(vols[(i + 1) % vols.size].index)
            })
        }
        item { ListRow("Empezar antes", trailing = "${s.marginBefore} min", onClick = { s.updateMargins((s.marginBefore + 1) % 11, s.marginAfter) }) }
        item { ListRow("Terminar después", trailing = "${s.marginAfter} min", onClick = { s.updateMargins(s.marginBefore, (s.marginAfter + 5) % 35) }) }

        item { T("Cuenta y seguridad", Type.small, p.muted, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
        item {
            val a = acct
            val exp = a?.expires?.let { SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(it)) } ?: "—"
            ListRow(
                "Servidor $host",
                subtitle = if (a == null) "Comprobando…" else "Estado: ${a.status} · caduca: $exp · conexiones: ${a.activeConnections}/${a.maxConnections}",
                onClick = {},
            )
        }
        item {
            ListRow(
                if (app.vault.hasPin()) "Cambiar PIN" else "Crear PIN",
                subtitle = "Bloquea Ajustes para que nadie vea ni cambie la cuenta",
                onClick = { pinMode = "new" },
            )
        }
        if (app.vault.hasPin()) {
            item { ListRow("Pedir PIN al abrir la app", trailing = if (s.pinOnStart) "Sí" else "No", onClick = { s.updatePinOnStart(!s.pinOnStart) }) }
            item {
                ListRow("Quitar PIN", subtitle = "También quita el bloqueo de las carpetas", onClick = {
                    app.vault.setPin(null); s.updatePinOnStart(false); app.folders.clearLocks(); msg = "PIN eliminado"
                })
            }
        }
        item {
            val f = app.folders
            f.version
            val hidden = com.visor.tv.data.Folders.TYPES.sumOf { f.hidden(it).size }
            val locked = com.visor.tv.data.Folders.TYPES.sumOf { f.locked(it).size }
            ListRow(
                "Carpetas",
                subtitle = "Ocultar, ordenar o bloquear con PIN las carpetas de TV, películas y series",
                trailing = listOfNotNull(
                    hidden.takeIf { it > 0 }?.let { "$it ocultas" },
                    locked.takeIf { it > 0 }?.let { "$it con PIN" },
                ).joinToString(" · ").ifBlank { "Todas visibles" },
                onClick = { foldersOpen = true },
            )
        }
        item {
            val e = app.epg
            e.version
            val last = e.lastUpdate
            ListRow(
                "Guía completa",
                subtitle = when {
                    e.updating -> "Descargando… puede tardar un minuto"
                    e.status != null -> e.status
                    e.ready -> "${e.programmes} programas · actualizada " +
                        SimpleDateFormat("d MMM, HH:mm", Locale("es")).format(Date(last)) + " · se renueva sola cada 12 h"
                    else -> "Varios días de programación y búsqueda de programas. Pulsa para descargarla"
                },
                trailing = if (e.updating) "…" else "Actualizar",
                onClick = { if (!e.updating) scope.launch { e.refresh(app.repo) } },
            )
        }
        item { ListRow("Actualizar la lista automáticamente", subtitle = "Canales, películas y series nuevas del proveedor", trailing = s.refreshEvery.label, onClick = { s.updateRefresh(s.refreshEvery.next()) }) }
        item {
            val last = app.repo.lastRefresh
            ListRow(
                if (app.repo.refreshing) "Actualizando la lista…" else "Actualizar la lista ahora",
                subtitle = if (last > 0) "Última actualización: " + SimpleDateFormat("d MMM, HH:mm", Locale("es")).format(Date(last)) else "Aún no se ha actualizado",
                onClick = { if (!app.repo.refreshing) scope.launch { msg = if (app.repo.refreshAll()) "Lista actualizada" else "No se pudo actualizar: revisa la conexión" } },
            )
        }
        item { ListRow("Vaciar caché de listas", subtitle = "Vuelve a descargar carpetas y fichas", onClick = { app.repo.clearCache(); msg = "Caché vaciada" }) }
        item { ListRow("Cerrar sesión", subtitle = "Borra la cuenta de este aparato", onClick = { confirmLogout = true }) }
        if (msg != null) item { T(msg!!, Type.small, p.muted, Modifier.padding(12.dp)) }
        item { T("Copia de seguridad", Type.small, p.muted, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
        item {
            val last = backup.lastBackup()
            ListRow(
                "Crear copia de seguridad",
                subtitle = (if (last > 0) "Última: ${com.visor.tv.data.Backup.dateOf(last)}. " else "") +
                    "Favoritos, progreso, recientes, ajustes y grabaciones programadas",
                onClick = { scope.launch { msg = "Guardando…"; msg = backup.export().message } },
            )
        }
        item {
            ListRow(
                "Restaurar copia de seguridad",
                subtitle = "La cuenta no se guarda en la copia: tendrás que volver a entrar con tus datos",
                onClick = {
                    scope.launch {
                        val d = backup.peek()
                        if (d == null) msg = "No se encontró ninguna copia (${com.visor.tv.data.Backup.FILE_NAME})." else toRestore = d
                    }
                },
            )
        }
        item { T("Aplicación", Type.small, p.muted, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
        item {
            ListRow(
                "Buscar actualizaciones",
                subtitle = "Tienes la versión ${app.updater.current}",
                onClick = {
                    scope.launch {
                        msg = "Buscando…"
                        msg = if (app.updater.check(manual = true) == null) "Ya tienes la última versión" else null
                    }
                },
            )
        }
    }

    toRestore?.let { d ->
        ActionsDialog(
            "¿Restaurar la copia?",
            listOf(
                "Sí, restaurar" to {
                    scope.launch {
                        val r = backup.restore(d)
                        msg = r.message
                        if (r.ok) { kotlinx.coroutines.delay(1500); backup.restartApp() }
                    }
                    Unit
                },
                "Cancelar" to {},
            ),
            onDismiss = { toRestore = null },
            subtitle = "Copia del ${com.visor.tv.data.Backup.dateOf(d.created)}: ${d.favorites.size} favoritos, " +
                "${d.progress.size} a medias, ${d.recordings.size} grabaciones programadas. Sustituye lo que tienes ahora.",
        )
    }

    if (confirmLogout) {
        ActionsDialog(
            "¿Cerrar sesión?",
            listOf("Sí, borrar la cuenta" to { app.repo.logout(); nav.settingsUnlocked = false }, "Cancelar" to {}),
            onDismiss = { confirmLogout = false },
            subtitle = "Favoritos y grabaciones se conservan.",
        )
    }
}


/** Se queda solo con esquema, host y puerto: https://host:8443/get.php?... → https://host:8443 */
fun baseOf(url: String): String = try {
    val u = java.net.URI(url.trim())
    if (u.scheme == null || u.host == null) url.trim().trimEnd('/')
    else u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
} catch (e: Exception) {
    url.trim().trimEnd('/')
}

/** Reconoce enlaces get.php / player_api.php con usuario y contraseña y los separa. */
fun parseXtreamLink(url: String): Account? {
    if (!url.contains("username=") || !url.contains("password=")) return null
    return try {
        val q = java.net.URI(url).rawQuery ?: return null
        val params = q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val u = params["username"] ?: return null
        val pw = params["password"] ?: return null
        Account(baseOf(url), u, pw)
    } catch (e: Exception) {
        null
    }
}
