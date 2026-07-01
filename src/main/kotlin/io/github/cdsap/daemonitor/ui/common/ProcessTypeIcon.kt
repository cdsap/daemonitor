package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cdsap.daemonitor.domain.model.ProcessType
import androidx.compose.material3.Text

/**
 * A small per-process-type glyph shown next to the type label in the Live Monitor. Gradle's mascot
 * is the elephant 🐘, so the daemon gets a plain elephant and the wrapper an elephant wearing a
 * tool badge (it *invokes* the build). Kotlin has no emoji, so its mark is drawn directly. Emoji
 * are already used elsewhere in this UI (the 🔒 permission glyph), so they render here too.
 */
@Composable
fun ProcessTypeIcon(type: ProcessType, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    when (type) {
        ProcessType.GRADLE_DAEMON -> Emoji("🐘", size, modifier)
        ProcessType.GRADLE_WRAPPER -> WrapperGlyph(size, modifier)
        ProcessType.KOTLIN_DAEMON -> KotlinLogo(modifier.size(size))
        ProcessType.TEST_WORKER -> Emoji("🧪", size, modifier)
        ProcessType.JAVA_GRADLE_RELATED -> Emoji("☕", size, modifier)
    }
}

@Composable
private fun Emoji(glyph: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = (size.value * 0.85f).sp, fontFamily = FontFamily.Default)
    }
}

/** Gradle wrapper = the elephant carrying a tool: emoji with a small wrench badge in the corner. */
@Composable
private fun WrapperGlyph(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size)) {
        Text("🐘", fontSize = (size.value * 0.8f).sp, modifier = Modifier.align(Alignment.TopStart))
        Icon(
            Icons.Filled.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.55f)
                .clip(CircleShape)
                .background(LocalAccentColors.current.brand)
                .padding(1.dp),
        )
    }
}

/**
 * The Kotlin mark: a square whose right edge is notched inward to the center, drawn with the
 * official orange→magenta→violet diagonal gradient. Single closed path, so it scales crisply at
 * any icon size.
 */
@Composable
fun KotlinLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w, h)          // bottom-right
            lineTo(0f, h)         // bottom-left
            lineTo(0f, 0f)        // top-left
            lineTo(w, 0f)         // top-right
            lineTo(w / 2f, h / 2f) // center notch
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFE44857), Color(0xFFC711E1), Color(0xFF7F52FF)),
                start = Offset(w, 0f), // top-right
                end = Offset(0f, h),   // bottom-left
            ),
        )
    }
}
