package io.github.cdsap.daemonitor.ui.common

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

/** Shared corner radii so cards, tiles, and pills feel like one family. */
object Radius {
    val pill = 999.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
}

/**
 * Semantic accent colors used for status pills and stat-tile accents. Kept outside the Material
 * scheme because they are meaning-bearing (success / failure / automation), not surface roles —
 * each pairs a saturated foreground with a soft tinted container so a pill reads at a glance
 * without shouting.
 */
object Accent {
    val success = Color(0xFF1B7A4B)
    val successBg = Color(0xFFE3F3E9)
    val danger = Color(0xFFB3261E)
    val dangerBg = Color(0xFFFBE7E6)
    val warn = Color(0xFF9A6700)
    val warnBg = Color(0xFFFCF1DC)
    val info = Color(0xFF2C5D9B)
    val infoBg = Color(0xFFE6EEF9)
    val neutral = Color(0xFF55605C)
    val neutralBg = Color(0xFFEDF1F0)
    val brand = Color(0xFF00695C)
    val brandBg = Color(0xFFD9EEEA)
}

private val WatcherColors = lightColorScheme(
    primary = Color(0xFF00695C),          // teal 800 — Gradle-adjacent accent
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EEEA),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF455A64),        // blue-grey 700
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18211F),
    surfaceVariant = Color(0xFFEDF1F0),   // header/zebra background
    onSurfaceVariant = Color(0xFF49544F),
    outline = Color(0xFFC4CDC9),
    outlineVariant = Color(0xFFDDE3E1),
    background = Color(0xFFF4F7F6),
    error = Color(0xFFB3261E),
)

@Composable
fun WatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatcherColors,
        typography = Typography(),
        content = content,
    )
}
