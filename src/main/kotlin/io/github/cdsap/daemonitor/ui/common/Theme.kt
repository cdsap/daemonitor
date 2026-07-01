package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cdsap.daemonitor.store.AppearancePreference

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
@Immutable
internal data class AccentColors(
    val success: Color,
    val successBg: Color,
    val danger: Color,
    val dangerBg: Color,
    val warn: Color,
    val warnBg: Color,
    val info: Color,
    val infoBg: Color,
    val neutral: Color,
    val neutralBg: Color,
    val brand: Color,
    val brandBg: Color,
)

internal val LocalAccentColors = staticCompositionLocalOf { LightAccents }

private val LightAccents = AccentColors(
    success = Color(0xFF1B7A4B), successBg = Color(0xFFE3F3E9),
    danger = Color(0xFFB3261E), dangerBg = Color(0xFFFBE7E6),
    warn = Color(0xFF8A5C00), warnBg = Color(0xFFFCF1DC),
    info = Color(0xFF2C5D9B), infoBg = Color(0xFFE6EEF9),
    neutral = Color(0xFF55605C), neutralBg = Color(0xFFEDF1F0),
    brand = Color(0xFF00695C), brandBg = Color(0xFFD9EEEA),
)

private val DarkAccents = AccentColors(
    success = Color(0xFF7DD6A5), successBg = Color(0xFF173D2B),
    danger = Color(0xFFFFB4AB), dangerBg = Color(0xFF55211E),
    warn = Color(0xFFFFD180), warnBg = Color(0xFF4A3512),
    info = Color(0xFFA9C7F5), infoBg = Color(0xFF243A59),
    neutral = Color(0xFFC4CCC7), neutralBg = Color(0xFF343A37),
    brand = Color(0xFF80D5C4), brandBg = Color(0xFF17443C),
)

internal val WatcherLightColors = lightColorScheme(
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

internal val WatcherDarkColors = darkColorScheme(
    primary = Color(0xFF80D5C4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF165047),
    onPrimaryContainer = Color(0xFFA1F2DF),
    secondary = Color(0xFFB8C8C3),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF2A2E2C),
    onSurfaceVariant = Color(0xFFC0C8C3),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
    background = Color(0xFF111413),
    error = Color(0xFFFFB4AB),
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
fun WatcherTheme(
    appearance: AppearancePreference = AppearancePreference.SYSTEM,
    systemDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (appearance) {
        AppearancePreference.SYSTEM -> systemDarkTheme
        AppearancePreference.LIGHT -> false
        AppearancePreference.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAccentColors provides if (darkTheme) DarkAccents else LightAccents,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) WatcherDarkColors else WatcherLightColors,
            typography = WatcherTypography,
            content = content,
        )
    }
}
