package com.dvgame.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object DvColors {
    val Green = Color(0xFF4EB712)
    val GreenDeep = Color(0xFF10230D)
    val Panel = Color(0xFF121316)
    val Drawer = Color(0xFF101114)
    val Line = Color(0x16FFFFFF)
    val MutedDark = Color(0xFF676A71)
    val Primary = Color(0xFFA78BFA)
    val PrimaryDeep = Color(0xFF7C3AED)
    val PrimaryDark = Color(0xFF4C1D95)
    val PrimaryLight = Color(0xFFDDD6FE)
    val AccentPink = Color(0xFFE879F9)
    val Amber = Color(0xFFFBBF24)
    val Background = Color(0xFF0B0C0E)
    val BackgroundDeep = Color(0xFF090A0C)
    val Surface = Color(0xFF121316)
    val SurfaceHigh = Color(0xFF17181C)
    val CardBorder = Color(0x16FFFFFF)
    val Divider = Color(0x16FFFFFF)
    val WarmBorder = Color(0xFFD8C8A6)
    val Glow = Color(0x29777A80)
    val Success = Color(0xFF34D399)
    val Danger = Color(0xFFF87171)
    val Text = Color(0xFFF2F1ED)
    val Muted = Color(0xFF777A80)

    val ActiveButton = Brush.linearGradient(
        0f to Color(0xFF7C3AED),
        0.55f to Color(0xFF8B5CF6),
        1f to Color(0xFFC084FC),
    )
    val ScreenBackground = Brush.verticalGradient(
        0f to Color(0xFF0D0E11),
        0.70f to Color(0xFF090A0C),
        1f to Color(0xFF090A0C),
    )
}

private val DvColorScheme = darkColorScheme(
    primary = DvColors.Primary,
    onPrimary = Color(0xFF1A1033),
    primaryContainer = DvColors.PrimaryDark,
    onPrimaryContainer = DvColors.Text,
    secondary = DvColors.AccentPink,
    onSecondary = Color(0xFF2A0A2E),
    background = DvColors.Background,
    onBackground = DvColors.Text,
    surface = DvColors.Surface,
    onSurface = DvColors.Text,
    surfaceVariant = DvColors.SurfaceHigh,
    onSurfaceVariant = DvColors.Muted,
    outline = DvColors.Divider,
    outlineVariant = DvColors.CardBorder,
    error = DvColors.Danger,
    onError = Color(0xFF2A060A),
)

@Composable
fun DvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DvColorScheme,
        typography = Typography(),
        content = content,
    )
}
