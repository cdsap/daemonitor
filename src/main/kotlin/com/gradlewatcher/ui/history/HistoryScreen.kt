package com.gradlewatcher.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gradlewatcher.domain.model.Build
import com.gradlewatcher.domain.model.Source
import com.gradlewatcher.ui.common.EmptyState
import com.gradlewatcher.ui.common.LogView
import com.gradlewatcher.ui.common.PrivacyNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onProject: (String?) -> Unit,
    onTimeRange: (TimeRange) -> Unit,
) {
    var selected by remember { mutableStateOf<Build?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Filters(state, onProject, onTimeRange)
        Divider()
        if (state.isEmptyResult) {
            EmptyState("No builds match the current filters.", modifier = Modifier.weight(1f))
        } else {
            Row(modifier = Modifier.weight(1f)) {
                BuildTable(
                    builds = state.builds,
                    onSelect = { selected = it },
                    modifier = Modifier.weight(1.4f),
                )
                HistoryDetailPanel(selected, modifier = Modifier.weight(1f).padding(12.dp))
            }
        }
        PrivacyNotice()
    }
}

@Composable
private fun Filters(state: HistoryUiState, onProject: (String?) -> Unit, onTimeRange: (TimeRange) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeRange.entries.forEach { range ->
            FilterChip(
                selected = state.filter.timeRange == range,
                onClick = { onTimeRange(range) },
                label = { Text(range.label) },
            )
        }
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
private fun BuildTable(builds: List<Build>, onSelect: (Build) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(builds) { b ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(b) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(formatTime(b.startTimeMs), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(b.projectPath?.substringAfterLast('/') ?: "—", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(b.durationSeconds), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
                Text(b.peakMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (b.peakMemoryMb == null) Color.Gray else Color.Unspecified)
                Text(b.finalStatus.name.lowercase(), modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                SourceLabel(b.inferredSource, modifier = Modifier.weight(0.7f))
            }
            Divider()
        }
    }
}

@Composable
private fun HistoryDetailPanel(build: Build?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (build == null) {
            Text("Select a build to see details.", color = Color.Gray)
            return
        }
        Text("Build ${build.buildId}", fontWeight = FontWeight.SemiBold)
        DetailRow("Project", build.projectPath ?: "—")
        DetailRow("Duration", formatDuration(build.durationSeconds))
        DetailRow("Peak memory", build.peakMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)")
        DetailRow("Avg memory", build.avgMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)")
        DetailRow("Peak CPU", build.peakCpuPercent?.let { "%.0f%%".format(it) } ?: "not sampled (<2s)")
        DetailRow("Final status", build.finalStatus.name.lowercase())
        DetailRow("Source", build.inferredSource.name.lowercase())
        Text("Build log excerpt — lines captured during build window",
            style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
        val snippet = build.logSnippet
        if (snippet.isNullOrBlank()) {
            Text("No log captured for this build window.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            LogView(snippet.lines(), modifier = Modifier.weight(1f), autoScroll = false)
        }
    }
}

@Composable
private fun SourceLabel(source: Source, modifier: Modifier = Modifier) {
    val muted = source == Source.UNKNOWN
    Text(
        text = source.name.lowercase(),
        modifier = modifier,
        color = if (muted) Color.Gray else Color.Unspecified,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
private fun formatTime(ms: Long): String = TIME_FMT.format(Instant.ofEpochMilli(ms))
private fun formatDuration(seconds: Double?): String =
    seconds?.let { if (it < 1) "%.0f ms".format(it * 1000) else "%.1f s".format(it) } ?: "—"
