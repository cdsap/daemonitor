package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A single headline metric tile for the summary header, with an optional accent + icon. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color? = null,
) {
    val tint = accent ?: MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Slim accent rail on the leading edge gives each tile a quiet pop of color.
            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(tint))
            Column(modifier = Modifier.padding(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(Space.xs))
                    }
                    Text(
                        label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Space.xs))
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A titled, rounded panel. [content] gets a weighted area so it can fill/scroll. */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(Space.md)) { content() }
        }
    }
}

/** Compact screen toolbar used above data-heavy desktop views. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp).padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        trailing()
    }
}
