package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.SectionCard
import io.github.cdsap.daemonitor.ui.common.Space

@Composable
fun MemoryGraph(rows: List<MemoryGraphRow>, modifier: Modifier = Modifier) {
    SectionCard("Memory allocation", modifier) {
        LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            items(rows, key = { it.pid }) { row ->
                MemoryGraphRow(row)
            }
        }
    }
}

@Composable
private fun MemoryGraphRow(row: MemoryGraphRow) {
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Column(modifier = Modifier.weight(0.9f)) {
            Text(
                "${row.title} · PID ${row.pid}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(modifier = Modifier.weight(1.7f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            MemoryBar(
                label = "RSS ${row.rssMemoryMb} MB",
                fraction = row.rssFraction,
                foreground = LocalAccentColors.current.info,
                background = LocalAccentColors.current.infoBg,
            )
            MemoryBar(
                label = row.heapLimitMb?.let { "Heap limit $it MB" } ?: "Heap limit unavailable",
                fraction = row.heapFraction ?: 0f,
                foreground = LocalAccentColors.current.warn,
                background = LocalAccentColors.current.neutralBg,
                muted = row.heapFraction == null,
            )
        }
    }
}

@Composable
private fun MemoryBar(
    label: String,
    fraction: Float,
    foreground: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    muted: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(background),
        ) {
            if (!muted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(foreground),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Text(
            label,
            modifier = Modifier.width(124.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
