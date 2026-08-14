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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source

/**
 * A small, soft status pill: tinted container + saturated label, with an optional leading marker —
 * either a vector [icon] or a unicode [glyph]. The single primitive behind every colored
 * status/source indicator so they read as one visual language instead of a scatter of ad-hoc badges.
 *
 * Label text is single-line with ellipsis so narrow table columns (History) clip cleanly instead
 * of wrapping onto a second line.
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
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("pill-$text"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(11.dp),
            )
            glyph != null -> Text(
                text = glyph,
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Colored pill for a build's final status — a per-outcome glyph + green/red/amber/grey tint. */
@Composable
fun StatusPill(status: FinalStatus, modifier: Modifier = Modifier) {
    val (fg, bg, label, glyph) = when (status) {
        FinalStatus.SUCCESS -> StatusStyle(LocalAccentColors.current.success, LocalAccentColors.current.successBg, "success", "✓")
        FinalStatus.FAILED -> StatusStyle(LocalAccentColors.current.danger, LocalAccentColors.current.dangerBg, "failed", "✗")
        FinalStatus.INTERRUPTED -> StatusStyle(LocalAccentColors.current.warn, LocalAccentColors.current.warnBg, "interrupted", "⚠")
        FinalStatus.COMPLETED_NO_OUTCOME -> StatusStyle(LocalAccentColors.current.neutral, LocalAccentColors.current.neutralBg, "completed", "◐")
    }
    Pill(label, fg, bg, modifier, glyph = glyph)
}

private data class StatusStyle(val fg: Color, val bg: Color, val label: String, val glyph: String)

/** Subtle pill for the inferred build source (terminal / IDE / unknown), with a matching icon. */
@Composable
fun SourcePill(source: Source, modifier: Modifier = Modifier) {
    val style = when (source) {
        Source.TERMINAL -> SourceStyle(LocalAccentColors.current.info, LocalAccentColors.current.infoBg, "terminal", Icons.Filled.Terminal)
        Source.IDE -> SourceStyle(LocalAccentColors.current.brand, LocalAccentColors.current.brandBg, "IDE", Icons.Filled.Code)
        Source.UNKNOWN -> SourceStyle(LocalAccentColors.current.neutral, LocalAccentColors.current.neutralBg, "unknown", Icons.Filled.HelpOutline)
    }
    Pill(style.label, style.fg, style.bg, modifier, icon = style.icon)
}

private data class SourceStyle(val fg: Color, val bg: Color, val label: String, val icon: ImageVector)
