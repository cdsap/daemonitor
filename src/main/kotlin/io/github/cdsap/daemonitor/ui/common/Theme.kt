package io.github.cdsap.daemonitor.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val sm = 4.dp
    val md = 6.dp
    val lg = 8.dp
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
    primary = Color(0xFF28735F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCECE6),
    onPrimaryContainer = Color(0xFF143E33),
    secondary = Color(0xFF4D5A66),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202522),
    surfaceVariant = Color(0xFFF1F3F2),
    onSurfaceVariant = Color(0xFF626A66),
    outline = Color(0xFFBCC3BF),
    outlineVariant = Color(0xFFDADFDA),
    background = Color(0xFFF6F7F7),
    error = Color(0xFFB23A35),
)

private val WatcherTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun WatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatcherColors,
        typography = WatcherTypography,
        content = content,
    )
}
