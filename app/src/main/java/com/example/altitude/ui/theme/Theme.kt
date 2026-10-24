package com.example.altitude.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF6FD3FF)
val Good = Color(0xFF7BE495)
val Warn = Color(0xFFFFC46B)
val Bad = Color(0xFFFF7B72)
val Muted = Color(0xFF93A4BE)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00202E),
    secondary = Good,
    onSecondary = Color(0xFF00281A),
    background = Color(0xFF0A101C),
    onBackground = Color(0xFFE8EFFA),
    surface = Color(0xFF141D2E),
    onSurface = Color(0xFFE8EFFA),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Muted,
    outline = Color(0xFF2A3A55),
    error = Bad
)

@Composable
fun AltimeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
