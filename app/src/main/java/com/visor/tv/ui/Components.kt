package com.visor.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Elemento enfocable con el mando: OK pulsa, el botón Menú (≡) abre acciones.
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    focusRequester: FocusRequester? = null,
    scaleOnFocus: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { e ->
                if (onMenu != null && e.key == Key.Menu) {
                    if (e.type == KeyEventType.KeyUp) onMenu()
                    true
                } else false
            }
            .graphicsLayer {
                val s = if (scaleOnFocus && focused) 1.06f else 1f
                scaleX = s; scaleY = s
            }
            .clip(shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        content = { content(focused) },
    )
}

@Composable
fun T(text: String, style: TextStyle, color: Color = LocalPalette.current.text, modifier: Modifier = Modifier, maxLines: Int = 1, minLines: Int = 1) {
    Text(text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, minLines = minLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dot: Color? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    trailing: String? = null,
) {
    val p = LocalPalette.current
    Focusable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        focusRequester = focusRequester,
        onFocused = onFocused,
    ) { focused ->
        val bg = when { focused -> p.focusBg; selected -> p.surface; else -> Color.Transparent }
        val fg = if (focused) p.focusText else p.text
        Row(
            modifier = Modifier
                .background(bg)
                .border(1.dp, if (focused || selected) Color.Transparent else p.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            T(text, Type.label, fg)
            if (trailing != null) {
                Spacer(Modifier.weight(1f, fill = false))
                T(trailing, Type.small, fg.copy(alpha = 0.7f))
            }
        }
    }
}

/** Fila de lista (carpetas, canales, ajustes). */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    dot: Color? = null,
) {
    val p = LocalPalette.current
    Focusable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        onMenu = onMenu,
        onFocused = onFocused,
        focusRequester = focusRequester,
    ) { focused ->
        val bg = when { focused -> p.focusBg; selected -> p.surface; else -> Color.Transparent }
        val fg = if (focused) p.focusText else p.text
        Row(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading?.invoke()
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                T(title, Type.label, fg)
                if (!subtitle.isNullOrBlank()) T(subtitle, Type.small, fg.copy(alpha = 0.7f))
            }
            if (trailing != null) T(trailing, Type.small, fg.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun Img(url: String, modifier: Modifier = Modifier, crop: Boolean = true) {
    val p = LocalPalette.current
    Box(modifier.background(p.placeholder)) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Póster 2:3 con título y barra de progreso opcional. */
@Composable
fun Poster(
    url: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
    subtitle: String? = null,
    progress: Float? = null,
    onMenu: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    wide: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val p = LocalPalette.current
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Focusable(onClick = onClick, onMenu = onMenu, onFocused = onFocused, shape = RoundedCornerShape(12.dp), scaleOnFocus = true, focusRequester = focusRequester) { focused ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                    .border(if (focused) 3.dp else 0.dp, if (focused) p.focusBg else Color.Transparent, RoundedCornerShape(12.dp))
            ) {
                Img(url, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                if (progress != null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().height(3.dp)
                            .clip(CircleShape).background(Color(0x55000000))
                    ) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                    }
                }
            }
        }
        T(title, Type.label)
        if (subtitle != null) T(subtitle, Type.small, p.muted)
    }
}

@Composable
fun Empty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.CenterStart) {
        T(text, Type.body, LocalPalette.current.muted, maxLines = 3)
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        T(text, Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), fg)
    }
}
