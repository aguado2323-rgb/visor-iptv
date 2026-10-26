package com.visor.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.visor.tv.data.SeriesItem
import com.visor.tv.data.VodStream
import com.visor.tv.ui.Pill
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visor.tv.Nav
import com.visor.tv.Route
import com.visor.tv.VisorApp
import com.visor.tv.data.FavItem
import com.visor.tv.data.LiveStream
import com.visor.tv.play
import com.visor.tv.ui.Empty
import com.visor.tv.ui.Focusable
import com.visor.tv.ui.Img
import com.visor.tv.ui.LocalPalette
import com.visor.tv.ui.Poster
import com.visor.tv.ui.T
import com.visor.tv.ui.TvButton
import com.visor.tv.ui.Type

/** Tarjeta de canal para las baldas de favoritos. */
@Composable
fun ChannelTile(f: FavItem, onClick: () -> Unit, onMenu: () -> Unit) {
    val p = LocalPalette.current
    Focusable(onClick = onClick, onMenu = onMenu, shape = RoundedCornerShape(16.dp), scaleOnFocus = true) { focused ->
        Column(
            Modifier.width(132.dp).height(84.dp).background(if (focused) p.focusBg else p.surface).padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Img(f.icon, Modifier.size(width = 36.dp, height = 24.dp).clip(RoundedCornerShape(4.dp)), crop = false)
                if (f.num > 0) T(f.num.toString(), Type.small, if (focused) p.focusText else p.muted)
            }
            T(f.name, Type.label, if (focused) p.focusText else p.text, maxLines = 2)
        }
    }
}

@Composable
fun FavoritesScreen(app: VisorApp, nav: Nav) {
    var section by rememberSaveable { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<LiveStream?>(null) }
    var timerFor by remember { mutableStateOf<LiveStream?>(null) }
    val labels = listOf("Canales", "Películas", "Series")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l ->
                val n = app.favorites.of(listOf("live", "vod", "series")[i]).size
                TvButton("$l ($n)", selected = section == i, onClick = { section = i }, onFocused = { section = i })
            }
        }
        when (section) {
            0 -> {
                val list = app.favorites.of("live").map { it.toLive() }
                if (list.isEmpty()) Empty("Sin canales favoritos. En TV, pulsa ≡ sobre un canal.")
                else ChannelList(app, list, onMenu = { menuFor = it })
            }
            1 -> {
                val list = app.favorites.of("vod").map { it.toVod() }
                if (list.isEmpty()) Empty("Sin películas favoritas. En Películas, pulsa ≡ sobre una película.")
                else MovieGrid(app, nav, list)
            }
            else -> {
                val list = app.favorites.of("series").map { it.toSeries() }
                if (list.isEmpty()) Empty("Sin series favoritas. En Series, pulsa ≡ sobre una serie.")
                else SeriesGrid(app, nav, list)
            }
        }
    }
    menuFor?.let { s -> ChannelActions(app, s, onTimer = { timerFor = s }, onDismiss = { menuFor = null }) }
    timerFor?.let { s -> NewTimerDialog(app, onDismiss = { timerFor = null }, presetChannel = s) }
}
