package com.x10pult.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF00897B),
    secondary = Color(0xFF26A69A),
    background = Color(0xFFF4F7F6),
    surface = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    secondary = Color(0xFF80CBC4),
)

@Composable
fun X10Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
