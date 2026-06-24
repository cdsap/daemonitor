package com.gradlewatcher.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Single 4-pt spacing scale so every screen uses the same rhythm (removes the "scattered" feel). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

private val WatcherColors = lightColorScheme(
    primary = Color(0xFF00695C),          // teal 800 — Gradle-adjacent accent
    onPrimary = Color.White,
    secondary = Color(0xFF455A64),        // blue-grey 700
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDF1F0),   // header/zebra background
    onSurfaceVariant = Color(0xFF3F4946),
    outlineVariant = Color(0xFFDDE3E1),
    background = Color(0xFFF7F9F8),
)

@Composable
fun WatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatcherColors,
        typography = Typography(),
        content = content,
    )
}
