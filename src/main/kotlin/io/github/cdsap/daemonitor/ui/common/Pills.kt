package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source

/**
 * A small, soft status pill: tinted container + saturated label, with an optional leading marker —
 * either a vector [icon] or a unicode [glyph]. The single primitive behind every colored
 * status/source indicator so they read as one visual language instead of a scatter of ad-hoc badges.
 */
@Composable
fun Pill(
    text: String,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    glyph: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
            glyph != null -> Text(glyph, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Colored pill for a build's final status — a per-outcome glyph + green/red/amber/grey tint. */
@Composable
fun StatusPill(status: FinalStatus, modifier: Modifier = Modifier) {
    val (fg, bg, label, glyph) = when (status) {
        FinalStatus.SUCCESS -> StatusStyle(Accent.success, Accent.successBg, "success", "✓")
        FinalStatus.FAILED -> StatusStyle(Accent.danger, Accent.dangerBg, "failed", "✗")
        FinalStatus.INTERRUPTED -> StatusStyle(Accent.warn, Accent.warnBg, "interrupted", "⚠")
        FinalStatus.COMPLETED_NO_OUTCOME -> StatusStyle(Accent.neutral, Accent.neutralBg, "completed", "◐")
    }
    Pill(label, fg, bg, modifier, glyph = glyph)
}

private data class StatusStyle(val fg: Color, val bg: Color, val label: String, val glyph: String)

/** Subtle pill for the inferred build source (terminal / IDE / unknown), with a matching icon. */
@Composable
fun SourcePill(source: Source, modifier: Modifier = Modifier) {
    val style = when (source) {
        Source.TERMINAL -> SourceStyle(Accent.info, Accent.infoBg, "terminal", Icons.Filled.Terminal)
        Source.IDE -> SourceStyle(Accent.brand, Accent.brandBg, "IDE", Icons.Filled.Code)
        Source.UNKNOWN -> SourceStyle(Accent.neutral, Accent.neutralBg, "unknown", Icons.Filled.HelpOutline)
    }
    Pill(style.label, style.fg, style.bg, modifier, icon = style.icon)
}

private data class SourceStyle(val fg: Color, val bg: Color, val label: String, val icon: ImageVector)
