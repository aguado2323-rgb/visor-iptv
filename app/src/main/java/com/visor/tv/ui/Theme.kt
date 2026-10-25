package com.visor.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.visor.tv.data.ThemeMode

data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val focusBg: Color,
    val focusText: Color,
    val placeholder: Color,
    val rec: Color = Color(0xFFE5484D),
)

val LightPalette = Palette(
    bg = Color(0xFFECEDEF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F5F6),
    text = Color(0xFF111214),
    muted = Color(0xFF555A62),
    line = Color(0xFFD6D8DC),
    focusBg = Color(0xFF111214),
    focusText = Color(0xFFFFFFFF),
    placeholder = Color(0xFFD3D7DD),
)

val OledPalette = Palette(
    bg = Color(0xFF000000),
    surface = Color(0xFF111214),
    surface2 = Color(0xFF1A1B1E),
    text = Color(0xFFF4F4F2),
    muted = Color(0xFF9A9DA3),
    line = Color(0xFF26282C),
    focusBg = Color(0xFFF4F4F2),
    focusText = Color(0xFF000000),
    placeholder = Color(0xFF15171A),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

object Type {
    val title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val tab = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val h2 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 11.sp)
}

@Composable
fun VisorTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val p = if (mode == ThemeMode.OLED) OledPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p, content = content)
}
