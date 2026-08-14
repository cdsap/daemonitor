package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A table column: a header label, a flex weight, and whether its cells are end-aligned (numbers). */
data class Col(val label: String, val weight: Float, val end: Boolean = false)

/** Shared header row. Using the same [cols] weights for the header and every body row is what
 *  makes columns actually line up — the root fix for the "scattered" tables. */
@Composable
fun TableHeader(cols: List<Col>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            cols.forEach { c ->
                Text(
                    text = c.label.uppercase(),
                    modifier = Modifier.weight(c.weight),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (c.end) TextAlign.End else TextAlign.Start,
                )
            }
        }
    }
}

/** A weighted cell slot that hosts arbitrary content (e.g. a status pill) while keeping the same
 *  column alignment as text cells. Content is width-bounded to the column so children can
 *  ellipsize instead of expanding the row. */
@Composable
fun RowScope.CellSlot(col: Col, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.weight(col.weight, fill = true),
        contentAlignment = if (col.end) androidx.compose.ui.Alignment.CenterEnd else androidx.compose.ui.Alignment.CenterStart,
    ) {
        content()
    }
}

/** A body cell aligned to its column's weight; single-line with ellipsis so long values never
 *  reflow the row. */
@Composable
fun RowScope.Cell(text: String, col: Col, muted: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.weight(col.weight),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (col.end) TextAlign.End else TextAlign.Start,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}
