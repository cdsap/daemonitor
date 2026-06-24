package com.gradlewatcher.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gradlewatcher.domain.model.GradleProcess
import com.gradlewatcher.domain.model.ProcessType
import com.gradlewatcher.ui.common.AutomatedBadge
import com.gradlewatcher.ui.common.Badges
import com.gradlewatcher.ui.common.ConcurrentBadge
import com.gradlewatcher.ui.common.EmptyState
import com.gradlewatcher.ui.common.LogView
import com.gradlewatcher.ui.common.MemoryBadge
import com.gradlewatcher.ui.common.PrivacyNotice

@Composable
fun LiveMonitorScreen(state: LiveUiState, onSelect: (Long) -> Unit, onClearSelection: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryHeader(state)
        Divider()
        if (state.isEmpty) {
            EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
        } else {
            val concurrent = Badges.concurrentSameProjectPids(state.processes)
            Row(modifier = Modifier.weight(1f)) {
                ProcessTable(
                    processes = state.processes,
                    concurrentPids = concurrent,
                    isDegraded = state::isPermissionDegraded,
                    onSelect = onSelect,
                    onClearSelection = onClearSelection,
                    modifier = Modifier.weight(1.6f),
                )
                Column(modifier = Modifier.weight(1f)) {
                    DetailPanel(state.detail, modifier = Modifier.weight(1f))
                    Divider()
                    Text("Daemon log", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                    LogView(state.tail, modifier = Modifier.weight(1f), autoScroll = true)
                }
            }
        }
        PrivacyNotice()
    }
}

@Composable
private fun SummaryHeader(state: LiveUiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Metric("Active processes", state.summary.activeProcessCount.toString())
            Metric("Total RSS", "${state.summary.totalRssMb} MB")
            Metric("Highest-mem PID", state.summary.highestMemoryPid?.toString() ?: "—")
            Metric("Active projects", state.summary.activeProjectCount.toString())
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProcessTable(
    processes: List<GradleProcess>,
    concurrentPids: Set<Long>,
    isDegraded: (GradleProcess) -> Boolean,
    onSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(processes) { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(p.pid) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(p.type.label(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(p.projectPath ?: "—", modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
                Text("${p.rssMemoryMb} MB", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
                Text(p.cpuPercent?.let { "%.0f%%".format(it) } ?: "—", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
                Badges.memoryBadge(p.rssMemoryMb)?.let { MemoryBadge(it) }
                if (p.pid in concurrentPids) ConcurrentBadge()
                if (p.automated) AutomatedBadge()
                if (isDegraded(p)) Text("🔒", style = MaterialTheme.typography.bodySmall)
            }
            Divider()
        }
    }
}

private fun ProcessType.label(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon"
    ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
    ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
    ProcessType.TEST_WORKER -> "Test worker"
    ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
}

@Composable
private fun DetailPanel(detail: DetailState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp)) {
        when (detail) {
            is DetailState.NoSelection ->
                Text("Select a process to see details.", color = Color.Gray)

            is DetailState.Selected -> ProcessDetails(detail.process, ended = false)
            is DetailState.Ended -> ProcessDetails(detail.lastKnown, ended = true)
        }
    }
}

@Composable
private fun ProcessDetails(p: GradleProcess, ended: Boolean) {
    Column {
        if (ended) {
            Text("Process ended", color = Color(0xFFB00020), fontWeight = FontWeight.Bold)
        }
        Text("PID ${p.pid} · ${p.type.label()}", fontWeight = FontWeight.SemiBold)
        DetailRow("Working dir", p.workingDirectory ?: "unavailable")
        DetailRow("RSS", "${p.rssMemoryMb} MB")
        DetailRow("Max heap (-Xmx)", p.maxHeapMb?.let { "$it MB" } ?: "unavailable")
        DetailRow("GC", p.gc ?: "—")
        Text("Command line", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
        Text(p.commandLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
    }
}
