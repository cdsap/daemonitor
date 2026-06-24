package com.gradlewatcher.ui.live

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gradlewatcher.domain.model.GradleProcess
import com.gradlewatcher.domain.model.ProcessType
import com.gradlewatcher.ui.common.AutomatedBadge
import com.gradlewatcher.ui.common.Badges
import com.gradlewatcher.ui.common.Cell
import com.gradlewatcher.ui.common.Col
import com.gradlewatcher.ui.common.ConcurrentBadge
import com.gradlewatcher.ui.common.EmptyState
import com.gradlewatcher.ui.common.LogView
import com.gradlewatcher.ui.common.MemoryBadge
import com.gradlewatcher.ui.common.PrivacyNotice
import com.gradlewatcher.ui.common.SectionCard
import com.gradlewatcher.ui.common.Space
import com.gradlewatcher.ui.common.StatTile
import com.gradlewatcher.ui.common.TableHeader

private val COLS = listOf(
    Col("Type", 1.4f),
    Col("Project", 2.2f),
    Col("RSS", 0.9f, end = true),
    Col("CPU", 0.7f, end = true),
    Col("Flags", 1.8f),
)

@Composable
fun LiveMonitorScreen(state: LiveUiState, onSelect: (Long) -> Unit, onClearSelection: () -> Unit) {
    val selectedPid = when (val d = state.detail) {
        is DetailState.Selected -> d.process.pid
        is DetailState.Ended -> d.lastKnown.pid
        else -> null
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SummaryHeader(state)
        if (state.isEmpty) {
            EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
        } else {
            val concurrent = Badges.concurrentSameProjectPids(state.processes)
            Row(modifier = Modifier.weight(1f).padding(Space.lg)) {
                Surface(
                    modifier = Modifier.weight(1.6f).fillMaxSize(),
                    shape = RoundedCornerShape(Space.md),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        TableHeader(COLS)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.processes, key = { it.pid }) { p ->
                                ProcessRow(p, p.pid == selectedPid, p.pid in concurrent, state::isPermissionDegraded, onSelect)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(Space.lg))
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    DetailCard(state.detail, modifier = Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.padding(Space.xs))
                    LogCard(state.tail, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        PrivacyNotice()
    }
}

@Composable
private fun SummaryHeader(state: LiveUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        StatTile("Active processes", state.summary.activeProcessCount.toString(), Modifier.weight(1f))
        StatTile("Total RSS", "${state.summary.totalRssMb} MB", Modifier.weight(1f))
        StatTile("Highest-mem PID", state.summary.highestMemoryPid?.toString() ?: "—", Modifier.weight(1f))
        StatTile("Active projects", state.summary.activeProjectCount.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun ProcessRow(
    p: GradleProcess,
    selected: Boolean,
    concurrent: Boolean,
    isDegraded: (GradleProcess) -> Boolean,
    onSelect: (Long) -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onSelect(p.pid) }
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Cell(p.type.label(), COLS[0])
        Cell(p.projectPath?.substringAfterLast('/') ?: "—", COLS[1], muted = p.projectPath == null)
        Cell("${p.rssMemoryMb} MB", COLS[2])
        Cell(p.cpuPercent?.let { "%.0f%%".format(it) } ?: "—", COLS[3])
        Row(modifier = Modifier.weight(COLS[4].weight), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Badges.memoryBadge(p.rssMemoryMb)?.let { MemoryBadge(it) }
            if (concurrent) ConcurrentBadge()
            if (p.automated) AutomatedBadge()
            if (isDegraded(p)) Text("🔒", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailCard(detail: DetailState, modifier: Modifier = Modifier) {
    SectionCard("Process detail", modifier) {
        when (detail) {
            is DetailState.NoSelection ->
                Text("Select a process to see details.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            is DetailState.Selected -> ProcessDetails(detail.process, ended = false)
            is DetailState.Ended -> ProcessDetails(detail.lastKnown, ended = true)
        }
    }
}

@Composable
private fun ProcessDetails(p: GradleProcess, ended: Boolean) {
    Column {
        if (ended) {
            Text("● Process ended", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.padding(Space.xs))
        }
        Text("PID ${p.pid} · ${p.type.label()}", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.padding(Space.xs))
        DetailRow("Working dir", p.workingDirectory ?: "unavailable")
        DetailRow("RSS", "${p.rssMemoryMb} MB")
        DetailRow("Max heap (-Xmx)", p.maxHeapMb?.let { "$it MB" } ?: "unavailable")
        DetailRow("GC", p.gc ?: "—")
        Spacer(Modifier.padding(Space.xs))
        Text("Command line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.commandLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogCard(tail: List<String>, modifier: Modifier = Modifier) {
    SectionCard("Daemon log", modifier) {
        LogView(tail, modifier = Modifier.fillMaxSize(), autoScroll = true)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun ProcessType.label(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon"
    ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
    ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
    ProcessType.TEST_WORKER -> "Test worker"
    ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
}
