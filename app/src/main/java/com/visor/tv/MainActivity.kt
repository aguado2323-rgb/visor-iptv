package com.visor.tv

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.visor.tv.data.PlayItem
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.VodStream
import com.visor.tv.player.PlayerActivity
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) {
    HOME("Inicio"), LIVE("TV"), GUIDE("Guía"), MOVIES("Películas"), SERIES("Series"),
    FAVS("Favoritos"), REC("Grabaciones"), SETTINGS("Ajustes")
}

sealed interface Route {
    data object Tabs : Route
    data class Movie(val vod: VodStream) : Route
    data class Show(val series: SeriesItem) : Route
}

/** Navegación mínima: una pila de pantallas y la pestaña activa. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Tabs)
    var tab by mutableStateOf(Tab.HOME)
    var settingsUnlocked by mutableStateOf(false)
    /** Elemento del catálogo desde el que se abrió la ficha; al volver, recupera el foco. */
    var returnKey by mutableStateOf<String?>(null)
    fun push(r: Route) { stack.add(r) }
    fun open(r: Route, fromKey: String) { returnKey = fromKey; stack.add(r) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

class MainActivity : ComponentActivity() {
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
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
}

@Composable
private fun Root(app: VisorApp, onExit: () -> Unit) {
    val p = LocalPalette.current
    val nav = remember { Nav() }
    // Guarda el estado de las pestañas (carpeta elegida, posición en la cuadrícula…) mientras se ve una ficha
    val tabsState = rememberSaveableStateHolder()
    var unlocked by remember { mutableStateOf(!(app.settings.pinOnStart && app.vault.hasPin())) }

    // Actualización automática de la lista: al abrir la app y luego cada hora comprueba si toca.
    LaunchedEffect(app.repo.account, app.settings.refreshEvery) {
        if (app.repo.account == null) return@LaunchedEffect
        while (true) {
            val every = app.settings.refreshEvery.hours * 3_600_000L
            if (every > 0 && System.currentTimeMillis() - app.repo.lastRefresh >= every) app.repo.refreshAll()
            delay(60 * 60_000L)
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
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
                    is Route.Movie -> MovieDetail(app, r.vod)
                    is Route.Show -> SeriesDetail(app, r.series)
                }
            }
        }
    }
}

@Composable
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

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                T(nav.tab.label, Type.title)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.onFocusChanged { tabsFocused = it.hasFocus },
                ) {
                    itemsIndexed(Tab.entries.toList()) { i, t ->
                        val active = t == nav.tab
                        Focusable(
                            onClick = { nav.tab = t },
                            onFocused = { nav.tab = t },
                            focusRequester = if (i == nav.tab.ordinal) firstTab else null,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        ) { focused ->
                            Column(
                                Modifier
                                    .background(if (focused) p.focusBg else Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                T(t.label, Type.tab, if (focused) p.focusText else if (active) p.text else p.muted)
                                Spacer(Modifier.height(3.dp))
                                Box(Modifier.width(18.dp).height(2.dp).background(if (active && !focused) p.text else Color.Transparent))
                            }
                        }
                    }
                }
            }
            T(now, Type.h2)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxSize()) {
            when (nav.tab) {
                Tab.HOME -> HomeScreen(app, nav)
                Tab.LIVE -> LiveScreen(app)
                Tab.GUIDE -> GuideScreen(app)
                Tab.MOVIES -> MoviesScreen(app, nav)
                Tab.SERIES -> SeriesScreen(app, nav)
                Tab.FAVS -> FavoritesScreen(app, nav)
                Tab.REC -> RecordingsScreen(app)
                Tab.SETTINGS -> SettingsScreen(app, nav)
            }
        }
    }
}

private fun clock(): String = SimpleDateFormat("HH:mm", Locale("es")).format(Date())
