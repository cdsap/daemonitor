package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.AutomatedBadge
import io.github.cdsap.daemonitor.ui.common.Badges
import io.github.cdsap.daemonitor.ui.common.Cell
import io.github.cdsap.daemonitor.ui.common.CellSlot
import io.github.cdsap.daemonitor.ui.common.Col
import io.github.cdsap.daemonitor.ui.common.ConcurrentBadge
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.LogView
import io.github.cdsap.daemonitor.ui.common.MemoryBadge
import io.github.cdsap.daemonitor.ui.common.PrivacyNotice
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.ProcessTypeIcon
import io.github.cdsap.daemonitor.ui.common.SectionCard
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.common.StatTile
import io.github.cdsap.daemonitor.ui.common.TableHeader

private val COLS = listOf(
    Col("Type", 1.4f),
    Col("Project", 2.0f),
    Col("RSS", 0.9f, end = true),
    Col("CPU", 0.7f, end = true),
    Col("Uptime", 1.0f, end = true),
    Col("Flags", 1.7f),
)

@Composable
fun LiveMonitorScreen(state: LiveUiState, onSelect: (Long) -> Unit, onClearSelection: () -> Unit) {
    val selectedPid = when (val d = state.detail) {
        is DetailState.Selected -> d.process.pid
        is DetailState.Ended -> d.lastKnown.pid
        else -> null
    }

    // A 1-second wall-clock tick so uptime advances smoothly between polls, not only when a
    // sampled value happens to change (an idle daemon's snapshot can be identical across polls).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SummaryHeader(state)
        if (state.isLoading) {
            EmptyState("Scanning for Gradle processes...", modifier = Modifier.weight(1f))
        } else if (state.isEmpty) {
            EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
        } else {
            val concurrent = Badges.concurrentSameProjectPids(state.processes)
            Row(modifier = Modifier.weight(1f).padding(Space.lg)) {
                Surface(
                    modifier = Modifier.weight(1.6f).fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        TableHeader(COLS)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.processes, key = { it.pid }) { p ->
                                ProcessRow(p, p.pid == selectedPid, p.pid in concurrent, nowMs, state::isPermissionDegraded, onSelect)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(Space.lg))
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    DetailCard(state.detail, nowMs, modifier = Modifier.weight(1f).fillMaxWidth())
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
    Column {
        ScreenHeader("Process monitor") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.size(7.dp).background(LocalAccentColors.current.success, androidx.compose.foundation.shape.CircleShape),
                )
                Text("MONITORING", style = MaterialTheme.typography.labelSmall, color = LocalAccentColors.current.success)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatTile("Processes", state.summary.activeProcessCount.toString(), Modifier.weight(1f), icon = Icons.Filled.Memory)
            StatTile("Resident memory", "${state.summary.totalRssMb} MB", Modifier.weight(1f), icon = Icons.Filled.Storage, accent = LocalAccentColors.current.info)
            StatTile("Peak memory PID", state.summary.highestMemoryPid?.toString() ?: "—", Modifier.weight(1f), icon = Icons.Filled.TrendingUp, accent = LocalAccentColors.current.warn)
            StatTile("Projects", state.summary.activeProjectCount.toString(), Modifier.weight(1f), icon = Icons.Filled.FolderOpen, accent = LocalAccentColors.current.brand)
        }
    }
}

@Composable
private fun ProcessRow(
    p: GradleProcess,
    selected: Boolean,
    concurrent: Boolean,
    nowMs: Long,
    isDegraded: (GradleProcess) -> Boolean,
    onSelect: (Long) -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onSelect(p.pid) }
            .padding(horizontal = Space.md, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        CellSlot(COLS[0]) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                ProcessTypeIcon(p.type, size = 16.dp)
                Text(p.type.label(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Cell(p.projectPath?.substringAfterLast('/') ?: "—", COLS[1], muted = p.projectPath == null)
        Cell("${p.rssMemoryMb} MB", COLS[2])
        Cell(p.cpuPercent?.let { "%.0f%%".format(it) } ?: "—", COLS[3])
        Cell(formatUptime(p.startTimeMs, nowMs), COLS[4])
        Row(modifier = Modifier.weight(COLS[5].weight), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Badges.memoryBadge(p.rssMemoryMb)?.let { MemoryBadge(it) }
            if (concurrent) ConcurrentBadge()
            if (p.automated) AutomatedBadge()
            if (isDegraded(p)) Text("🔒", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailCard(detail: DetailState, nowMs: Long, modifier: Modifier = Modifier) {
    SectionCard("Process detail", modifier) {
        when (detail) {
            is DetailState.NoSelection ->
                Text("Select a process to see details.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            is DetailState.Selected -> ProcessDetails(detail.process, ended = false, nowMs = nowMs)
            is DetailState.Ended -> ProcessDetails(detail.lastKnown, ended = true, nowMs = nowMs)
        }
    }
}

@Composable
private fun ProcessDetails(p: GradleProcess, ended: Boolean, nowMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("process-detail-scroll"),
    ) {
        if (ended) {
            Text("● Process ended", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.padding(Space.xs))
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            ProcessTypeIcon(p.type, size = 18.dp)
            Text("PID ${p.pid} · ${p.type.label()}", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.padding(Space.xs))
        DetailRow("Uptime", if (ended) "—" else formatUptime(p.startTimeMs, nowMs))
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

/** Compact, human-readable process uptime: "45s", "12m 03s", "3h 07m", "2d 4h". */
private fun formatUptime(startMs: Long, nowMs: Long): String {
    val total = ((nowMs - startMs) / 1000).coerceAtLeast(0)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h %02dm".format(minutes)
        minutes > 0 -> "${minutes}m %02ds".format(seconds)
        else -> "${seconds}s"
    }
}
