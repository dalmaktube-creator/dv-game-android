package com.dvgame.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2783DE),
    background = Color(0xFFF9F8F7),
    surface = Color.White,
    onBackground = Color(0xFF2C2C2B),
    outline = Color(0xFFE6E5E3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5E9FE8),
    background = Color(0xFF191919),
    surface = Color(0xFF202020),
    onBackground = Color.White,
    outline = Color(0xFF4D4D4B),
)

@Composable
fun DvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
