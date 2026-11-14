package com.visor.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import com.visor.tv.data.PlayItem
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.VodStream
import com.visor.tv.player.PlayerActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.visor.tv.player.MiniPlayer
import com.visor.tv.player.MiniPlayerWindow
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.T
import com.visor.tv.ui.Type
import com.visor.tv.ui.VisorTheme
import com.visor.tv.ui.screens.FavoritesScreen
import com.visor.tv.ui.screens.GuideScreen
import com.visor.tv.ui.screens.HomeScreen
import com.visor.tv.ui.screens.LiveScreen
import com.visor.tv.ui.screens.LoginScreen
import com.visor.tv.ui.screens.MovieDetail
import com.visor.tv.ui.screens.MoviesScreen
import com.visor.tv.ui.screens.PinPad
import com.visor.tv.ui.screens.RecordingsScreen
import com.visor.tv.ui.screens.SeriesDetail
import com.visor.tv.ui.screens.SeriesScreen
import com.visor.tv.ui.screens.SettingsScreen
import com.visor.tv.ui.screens.UpdateDialog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) {
    HOME("Inicio"), DISCOVER("Descubrir"), GUIDE("Guía"), LIVE("TV"), MOVIES("Películas"), SERIES("Series"),
    FAVS("Favoritos"), REC("Grabaciones"), SETTINGS("Ajustes")
}

sealed interface Route {
    data object Tabs : Route
    data class Movie(val vod: VodStream) : Route
    data class Show(val series: SeriesItem) : Route
    /** Ficha de un título de Descubrir (addons). */
    data class Addon(val meta: com.visor.tv.addons.MetaPreview) : Route
    /** Addons, cuentas debrid y preferencias de fuentes. */
    data object DiscoverSetup : Route
}

/** Navegación mínima: una pila de pantallas y la pestaña activa. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Tabs)
    var tab by mutableStateOf(Tab.HOME)
    var settingsUnlocked by mutableStateOf(false)
    /** Elemento desde el que se abrió la ficha que se acaba de cerrar; al volver, recupera el foco. */
    var returnKey by mutableStateOf<String?>(null)
    /** Una clave por pantalla abierta, para volver al elemento exacto aunque se encadenen fichas. */
    private val keys = mutableListOf<String?>()
    fun push(r: Route) { keys.add(null); returnKey = null; stack.add(r) }
    fun open(r: Route, fromKey: String) { keys.add(fromKey); returnKey = null; stack.add(r) }
    fun pop(): Boolean = if (stack.size > 1) {
        returnKey = keys.removeLastOrNull()
        stack.removeAt(stack.lastIndex); true
    } else false
}

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as VisorApp).internalNav = false
    }

    override fun onStop() {
        super.onStop()
        // Al salir de la app (botón Inicio) o al abrir otro vídeo, la ventana se cierra
        MiniPlayer.stop()
        // Al salir de la app (no al abrir el reproductor), las carpetas bloqueadas se vuelven a cerrar
        val app = application as VisorApp
        if (!app.internalNav && !isChangingConfigurations) app.folders.relockAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as VisorApp
        setContent {
            VisorTheme(app.settings.theme) {
                Root(app) { finish() }
            }
        }
    }
}

fun play(ctx: Context, list: List<PlayItem>, index: Int) {
    if (list.isEmpty()) return
    VisorApp.instance.queue.set(list, index)
    VisorApp.instance.internalNav = true
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
}

@Composable
private fun Root(app: VisorApp, onExit: () -> Unit) {
    val p = LocalPalette.current
    val nav = remember { Nav() }
    // Guarda el estado de las pestañas (carpeta elegida, posición en la cuadrícula…) mientras se ve una ficha
    val tabsState = rememberSaveableStateHolder()
    var unlocked by remember { mutableStateOf(!(app.settings.pinOnStart && app.vault.hasPin())) }

    // Al abrir la app, mira si hay una versión nueva publicada en GitHub
    LaunchedEffect(app.repo.account) { if (app.repo.account != null) app.updater.check() }

    // Actualización automática de la lista: al abrir la app y luego cada hora comprueba si toca.
    LaunchedEffect(app.repo.account, app.settings.refreshEvery) {
        if (app.repo.account == null) return@LaunchedEffect
        while (true) {
            val every = app.settings.refreshEvery.hours * 3_600_000L
            if (every > 0 && System.currentTimeMillis() - app.repo.lastRefresh >= every) app.repo.refreshAll()
            delay(60 * 60_000L)
        }
    }

    // Descubrir: los catálogos de los addons se empiezan a pedir al abrir la app, para que la pestaña salga ya llena
    LaunchedEffect(app.repo.account) {
        if (app.repo.account == null) return@LaunchedEffect
        delay(2_000)
        app.discover.prefetch()
    }

    // Guía completa (XMLTV): se descarga al abrir la app si tiene más de 12 horas, y luego cada hora se comprueba
    LaunchedEffect(app.repo.account) {
        if (app.repo.account == null) return@LaunchedEffect
        delay(5_000) // deja arrancar la interfaz primero
        while (true) {
            app.epg.refreshIfOld(app.repo, 12)
            delay(60 * 60_000L)
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            // Con un canal en ventana, Play/Pausa del mando lo vuelve a poner a pantalla completa
            .onPreviewKeyEvent { e ->
                if (MiniPlayer.active && e.type == KeyEventType.KeyUp &&
                    (e.key == Key.MediaPlayPause || e.key == Key.MediaPlay)
                ) { MiniPlayer.expand(ctx); true } else false
            }
    ) {
        when {
            app.repo.account == null -> LoginScreen(app)
            !unlocked -> PinPad(title = "Introduce el PIN", onDone = { pin ->
                if (app.vault.checkPin(pin)) { unlocked = true; true } else false
            })
            else -> {
                BackHandler {
                    if (!nav.pop()) {
                        if (nav.tab != Tab.HOME) nav.tab = Tab.HOME else onExit()
                    }
                }
                when (val r = nav.stack.last()) {
                    Route.Tabs -> tabsState.SaveableStateProvider("tabs") { TabsScaffold(app, nav) }
                    // key: cada ficha empieza de cero aunque se abra una desde otra
                    is Route.Movie -> androidx.compose.runtime.key(r, nav.stack.size) { MovieDetail(app, nav, r.vod) }
                    is Route.Show -> androidx.compose.runtime.key(r, nav.stack.size) { SeriesDetail(app, r.series) }
                    is Route.Addon -> androidx.compose.runtime.key(r, nav.stack.size) { com.visor.tv.ui.screens.AddonDetail(app, nav, r.meta) }
                    Route.DiscoverSetup -> com.visor.tv.ui.screens.DiscoverSetupScreen(app, nav)
                }
                if (app.updater.prompt) UpdateDialog(app)
                MiniPlayerWindow(Modifier.align(Alignment.BottomEnd).padding(24.dp))
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun TabsScaffold(app: VisorApp, nav: Nav) {
    val p = LocalPalette.current
    val firstTab = remember { FocusRequester() }
    val now by produceState(initialValue = clock()) {
        while (true) { delay(20_000); value = clock() }
    }
    var tabsFocused by remember { mutableStateOf(true) }
    // Al volver de una ficha, el foco lo recupera el elemento del catálogo; si no, va a las pestañas.
    LaunchedEffect(Unit) {
        if (nav.returnKey != null) {
            delay(1500) // si el elemento ya no está (lista actualizada…), el foco vuelve a las pestañas
            if (nav.returnKey == null) return@LaunchedEffect
            nav.returnKey = null
        }
        try { firstTab.requestFocus() } catch (_: Exception) { }
    }
    // Atrás desde el contenido sube primero a la barra de pestañas; desde ahí, a Inicio y luego sale.
    BackHandler(enabled = !tabsFocused) {
        try { firstTab.requestFocus() } catch (_: Exception) { }
    }

    // Inicio va a pantalla completa con el menú encima (fino y siempre oscuro); el resto, debajo de la cabecera.
    val home = nav.tab == Tab.HOME
    val guide = nav.tab == Tab.GUIDE
    val discover = nav.tab == Tab.DISCOVER
    // Inicio, Guía y Descubrir: menú fino sobre fondo oscuro
    val slim = home || guide || discover
    val homeFocus = remember { com.visor.tv.ui.screens.HomeFocus(firstTab) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    when {
                        // Inicio y Descubrir van a pantalla completa, con el menú encima de la imagen
                        home || discover -> Modifier
                        guide -> Modifier.padding(top = HEADER_H)
                        else -> Modifier.padding(start = 44.dp, end = 44.dp, top = HEADER_H)
                    }
                )
        ) {
            // Pantallas normales: título grande bajo el menú, como en la Guía, y el contenido debajo
            if (!slim) Column(Modifier.fillMaxSize()) {
                T(nav.tab.label, Type.title.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = (-0.5).sp), p.text, Modifier.padding(top = 6.dp))
                T(tabHint(nav.tab), Type.small.copy(fontSize = 12.sp), p.muted, Modifier.padding(top = 4.dp, bottom = 16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(app, nav, homeFocus) }
            } else TabContent(app, nav, homeFocus)
        }

        // El menú es siempre el mismo, en el mismo sitio y con el mismo tamaño: al cambiar de pestaña solo cambia
        // lo de debajo. En las pantallas oscuras (Inicio, Guía, Descubrir) usa los colores del tema oscuro.
        androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides if (slim) com.visor.tv.ui.OledPalette else p) {
            val hp = LocalPalette.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(HEADER_H)
                    .padding(start = 44.dp, end = 48.dp, top = 20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        // Solo se desplaza si no cabe (por ejemplo, con la ventana de un canal abierta)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .onFocusChanged { tabsFocused = it.hasFocus }
                        // Al subir desde el contenido se vuelve a la pestaña en la que estás, no a la más cercana
                        .focusProperties { enter = { firstTab } }
                        .focusGroup(),
                ) {
                    Tab.entries.forEach { t ->
                        val active = t == nav.tab
                        Focusable(
                            onClick = { nav.tab = t },
                            onFocused = { nav.tab = t },
                            focusRequester = if (active) firstTab else null,
                            // En Inicio, ↓ desde el menú va primero a los puntos de las novedades (← → las cambian)
                            modifier = Modifier.onPreviewKeyEvent { e ->
                                if (e.key != Key.DirectionDown || nav.tab != Tab.HOME || !homeFocus.dotsReady) false
                                else {
                                    if (e.type == KeyEventType.KeyDown) try { homeFocus.dots.requestFocus() } catch (_: Exception) { }
                                    true
                                }
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        ) { focused ->
                            Box(
                                Modifier
                                    .background(if (focused) hp.focusBg else if (active) hp.text.copy(alpha = 0.14f) else Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                T(
                                    t.label, Type.tab.copy(fontSize = 13.sp),
                                    if (focused) hp.focusText else if (active) hp.text else hp.text.copy(alpha = 0.72f),
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniPlayer.item?.let { mini ->
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        TvButton("⛶ ${mini.title}", onClick = { MiniPlayer.expand(ctx) })
                        TvButton("✕", onClick = { MiniPlayer.stop() })
                    }
                    T(now, Type.label.copy(fontSize = 14.sp), hp.text)
                }
            }
        }
    }
}

@Composable
private fun TabContent(app: VisorApp, nav: Nav, homeFocus: com.visor.tv.ui.screens.HomeFocus) {
    when (nav.tab) {
        Tab.HOME -> HomeScreen(app, nav, homeFocus)
        Tab.LIVE -> LiveScreen(app)
        Tab.GUIDE -> GuideScreen(app)
        Tab.MOVIES -> MoviesScreen(app, nav)
        Tab.SERIES -> SeriesScreen(app, nav)
        Tab.DISCOVER -> com.visor.tv.ui.screens.DiscoverScreen(app, nav)
        Tab.FAVS -> FavoritesScreen(app, nav)
        Tab.REC -> RecordingsScreen(app)
        Tab.SETTINGS -> SettingsScreen(app, nav)
    }
}

/** Frase bajo el título de cada pestaña. */
private fun tabHint(t: Tab): String = when (t) {
    Tab.LIVE -> "Los canales de tu lista, por carpetas, con lo que se emite ahora"
    Tab.MOVIES -> "Las películas de tu proveedor. ≡ sobre una carátula la añade a favoritos"
    Tab.SERIES -> "Las series de tu proveedor. ≡ sobre una carátula la añade a favoritos"
    Tab.FAVS -> "Canales, películas, series y títulos de Descubrir que has guardado"
    Tab.REC -> "Grabaciones programadas, en curso y terminadas"
    Tab.SETTINGS -> "Aspecto, reproducción, cuenta, Descubrir y copia de seguridad"
    else -> ""
}

/** Alto fijo del menú de pestañas: el contenido empieza siempre a la misma altura. */
private val HEADER_H = 56.dp

private fun clock(): String = SimpleDateFormat("HH:mm", Locale("es")).format(Date())
