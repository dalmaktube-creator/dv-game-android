package com.dvgame.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DvDark = darkColorScheme(
    primary = Color(0xFF6366F1), onPrimary = Color.White,
    background = Color(0xFF0F0F0F), surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF242424), error = Color(0xFFEF4444),
)
private val DvLight = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    background = Color(0xFFF5F5F5), surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F0), error = Color(0xFFDC2626),
)
@Composable
fun DvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DvDark else DvLight, content = content)
}
