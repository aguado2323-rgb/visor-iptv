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
            T("Introduce los datos Xtream de tu proveedor. Se guardan cifrados en este aparato y no se envían a ningún otro sitio.", Type.small, p.muted, maxLines = 3)
            field("Servidor (con puerto)", server, { server = it.trim() })
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
                        app.repo.login(Account(server, user, pass))
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
    var pinMode by remember { mutableStateOf<String?>(null) } // "new"
    var confirmLogout by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

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
        item { ListRow("Reproductor", subtitle = "Si un canal no va con uno, prueba con el otro", trailing = s.engine.label, onClick = { s.updateEngine(s.engine.next()) }) }
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
            item { ListRow("Quitar PIN", onClick = { app.vault.setPin(null); s.updatePinOnStart(false); msg = "PIN eliminado" }) }
        }
        item { ListRow("Vaciar caché de listas", subtitle = "Vuelve a descargar carpetas y fichas", onClick = { app.repo.clearCache(); msg = "Caché vaciada" }) }
        item { ListRow("Cerrar sesión", subtitle = "Borra la cuenta de este aparato", onClick = { confirmLogout = true }) }
        if (msg != null) item { T(msg!!, Type.small, p.muted, Modifier.padding(12.dp)) }
        item { T("Visor ${com.visor.tv.BuildConfig.VERSION_NAME}", Type.small, p.muted, Modifier.padding(12.dp)) }
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
