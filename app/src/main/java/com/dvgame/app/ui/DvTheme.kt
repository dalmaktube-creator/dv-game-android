package com.dvgame.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object DvColors {
    val Primary = Color(0xFFFF6A00)
    val PrimaryLight = Color(0xFFFF8A00)
    val Amber = Color(0xFFFF9D00)
    val Background = Color(0xFF151515)
    val Surface = Color(0xFF222222)
    val SurfaceHigh = Color(0xFF2A2A2A)
    val Divider = Color(0xFF3A4047)
    val WarmBorder = Color(0xFF5B3418)
    val Glow = Color(0x57FF6A00)
    val Success = Color(0xFF39DF59)
    val Danger = Color(0xFFFF3B47)
    val Text = Color(0xFFF4F4F5)
    val Muted = Color(0xFF9AA1AB)

    val ActiveButton = Brush.linearGradient(
        0f to Color(0xFF9F3700),
        0.58f to Color(0xFFFF6700),
        1f to Color(0xFFFF8A00),
    )
}

private val DvColorScheme = darkColorScheme(
    primary = DvColors.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9F3700),
    onPrimaryContainer = DvColors.Text,
    secondary = DvColors.Amber,
    onSecondary = Color.Black,
    background = DvColors.Background,
    onBackground = DvColors.Text,
    surface = DvColors.Surface,
    onSurface = DvColors.Text,
    surfaceVariant = DvColors.SurfaceHigh,
    onSurfaceVariant = DvColors.Muted,
    outline = DvColors.Divider,
    outlineVariant = DvColors.WarmBorder,
    error = DvColors.Danger,
    onError = Color.White,
)

@Composable
fun DvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DvColorScheme,
        typography = Typography(),
        content = content,
    )
}
