package com.gradlewatcher.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gradlewatcher.domain.model.Build
import com.gradlewatcher.domain.model.Source
import com.gradlewatcher.ui.common.Cell
import com.gradlewatcher.ui.common.Col
import com.gradlewatcher.ui.common.EmptyState
import com.gradlewatcher.ui.common.LogView
import com.gradlewatcher.ui.common.PrivacyNotice
import com.gradlewatcher.ui.common.SectionCard
import com.gradlewatcher.ui.common.Space
import com.gradlewatcher.ui.common.TableHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val COLS = listOf(
    Col("Started", 1.1f),
    Col("Project", 1.1f),
    Col("Duration", 0.7f, end = true),
    Col("Peak RSS", 1.0f, end = true),
    Col("Status", 0.9f),
    Col("Source", 0.7f),
    Col("Agent", 1.1f),
)

@Composable
fun HistoryScreen(state: HistoryUiState, onProject: (String?) -> Unit, onTimeRange: (TimeRange) -> Unit) {
    var selected by remember { mutableStateOf<Build?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Filters(state, onProject, onTimeRange)
        if (state.isEmptyResult) {
            EmptyState("No builds match the current filters.", modifier = Modifier.weight(1f))
        } else {
            Row(modifier = Modifier.weight(1f).padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
                Surface(
                    modifier = Modifier.weight(1.5f).fillMaxSize(),
                    shape = RoundedCornerShape(Space.md),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        TableHeader(COLS)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.builds, key = { it.buildId }) { b ->
                                BuildRow(b, b.buildId == selected?.buildId) { selected = b }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(Space.lg))
                SectionCard("Build detail", modifier = Modifier.weight(1f).fillMaxSize()) {
                    HistoryDetail(selected)
                }
            }
        }
        PrivacyNotice()
    }
}

@Composable
private fun Filters(state: HistoryUiState, onProject: (String?) -> Unit, onTimeRange: (TimeRange) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        TimeRange.entries.forEach { range ->
            FilterChip(
                selected = state.filter.timeRange == range,
                onClick = { onTimeRange(range) },
                label = { Text(range.label) },
            )
        }
        Spacer(Modifier.width(Space.sm))
        ProjectDropdown(state.projects, state.filter.projectPath, onProject)
    }
}

@Composable
private fun ProjectDropdown(projects: List<String>, selected: String?, onProject: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = true },
        label = { Text(selected?.substringAfterLast('/')?.let { "Project: $it" } ?: "All projects") },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("All projects") }, onClick = { onProject(null); expanded = false })
        projects.forEach { p ->
            DropdownMenuItem(text = { Text(p) }, onClick = { onProject(p); expanded = false })
        }
    }
}

@Composable
private fun BuildRow(b: Build, selected: Boolean, onSelect: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onSelect() }
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Cell(formatTime(b.startTimeMs), COLS[0])
        Cell(b.projectPath?.substringAfterLast('/') ?: "—", COLS[1], muted = b.projectPath == null)
        Cell(formatDuration(b.durationSeconds), COLS[2])
        Cell(b.peakMemoryMb?.let { "$it MB" } ?: "not sampled", COLS[3], muted = b.peakMemoryMb == null)
        Cell(b.finalStatus.name.lowercase().replace('_', ' '), COLS[4])
        Cell(b.inferredSource.name.lowercase(), COLS[5], muted = b.inferredSource == Source.UNKNOWN)
        Cell(b.agent ?: "—", COLS[6], muted = b.agent == null)
    }
}

@Composable
private fun HistoryDetail(build: Build?) {
    if (build == null) {
        Text("Select a build to see details.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Build ${build.buildId}", fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.padding(Space.xs))
        DetailRow("Project", build.projectPath ?: "—")
        DetailRow("Duration", formatDuration(build.durationSeconds))
        DetailRow("Peak memory", build.peakMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)")
        DetailRow("Avg memory", build.avgMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)")
        DetailRow("Peak CPU", build.peakCpuPercent?.let { "%.0f%%".format(it) } ?: "not sampled (<2s)")
        DetailRow("Final status", build.finalStatus.name.lowercase().replace('_', ' '))
        DetailRow("Source", build.inferredSource.name.lowercase())
        DetailRow("Agent", build.agent ?: "not detected")
        DetailRow("LLM provider", build.agent?.let { build.agentProvider ?: "unknown" } ?: "—")
        Spacer(Modifier.padding(Space.xs))
        Text(
            "Build log excerpt — captured during the build window",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Space.xs),
        )
        val snippet = build.logSnippet
        if (snippet.isNullOrBlank()) {
            Text("No log captured for this build window.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            LogView(snippet.lines(), modifier = Modifier.weight(1f).fillMaxWidth(), autoScroll = false)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
private fun formatTime(ms: Long): String = TIME_FMT.format(Instant.ofEpochMilli(ms))
private fun formatDuration(seconds: Double?): String =
    seconds?.let { if (it < 1) "%.0f ms".format(it * 1000) else "%.1f s".format(it) } ?: "—"
