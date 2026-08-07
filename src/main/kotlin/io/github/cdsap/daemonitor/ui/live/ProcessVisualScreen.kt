package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.ProcessTypeIcon
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.SectionCard
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.common.StatTile

@Composable
fun ProcessVisualScreen(state: LiveUiState) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader("Process visualizer")
        if (state.isLoading) {
            EmptyState("Scanning for Gradle processes...", modifier = Modifier.weight(1f))
        } else if (state.isEmpty) {
            EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
        } else {
            VisualDashboard(state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VisualDashboard(state: LiveUiState, modifier: Modifier = Modifier) {
    val rows = remember(state.processes) { MemoryGraphModel.fromProcesses(state.processes) }
    val defaultPid = remember(state.processes) { state.processes.maxByOrNull { it.rssMemoryMb }?.pid }
    var selectedPid by remember(state.processes) { mutableStateOf(defaultPid) }
    val selected = state.processes.firstOrNull { it.pid == selectedPid } ?: state.processes.first()
    val heapLimits = state.processes.mapNotNull { it.maxHeapMb }
    val heapLimitTotal = heapLimits.takeIf { it.isNotEmpty() }?.sum()?.let { "$it MB" } ?: "unavailable"

    Column(modifier = modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatTile("Processes", state.summary.activeProcessCount.toString(), Modifier.weight(1f), icon = Icons.Filled.Memory)
            StatTile("RSS total", "${state.summary.totalRssMb} MB", Modifier.weight(1f), icon = Icons.Filled.Storage, accent = LocalAccentColors.current.info)
            StatTile("Heap limits", heapLimitTotal, Modifier.weight(1f), icon = Icons.Filled.Timeline, accent = LocalAccentColors.current.warn)
            StatTile("Projects", state.summary.activeProjectCount.toString(), Modifier.weight(1f), accent = LocalAccentColors.current.brand)
        }

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            ProcessMap(
                processes = state.processes,
                rows = rows,
                selectedPid = selected.pid,
                onSelect = { selectedPid = it },
                modifier = Modifier.weight(1.45f).fillMaxHeight(),
            )
            ProcessInspector(selected, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ProcessMap(
    processes: List<GradleProcess>,
    rows: List<MemoryGraphRow>,
    selectedPid: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowsByPid = rows.associateBy { it.pid }
    val maxCpu = processes.mapNotNull { it.cpuPercent }.maxOrNull()?.takeIf { it > 0.0 } ?: 100.0
    SectionCard("Process map", modifier) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(processes, key = { it.pid }) { process ->
                val row = rowsByPid.getValue(process.pid)
                ProcessMapRow(
                    process = process,
                    graph = row,
                    cpuFraction = ((process.cpuPercent ?: 0.0) / maxCpu).toFloat().coerceIn(0f, 1f),
                    selected = process.pid == selectedPid,
                    onSelect = { onSelect(process.pid) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ProcessMapRow(
    process: GradleProcess,
    graph: MemoryGraphRow,
    cpuFraction: Float,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onSelect)
            .padding(vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProcessTypeIcon(process.type, size = 20.dp)
        Column(modifier = Modifier.weight(0.9f)) {
            Text(
                "${graph.title} · PID ${process.pid}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                process.projectPath?.substringAfterLast('/') ?: "unattributed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(modifier = Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            VisualBar("RSS ${process.rssMemoryMb} MB", graph.rssFraction, LocalAccentColors.current.info, LocalAccentColors.current.infoBg)
            VisualBar(
                graph.heapLimitMb?.let { "Heap limit $it MB" } ?: "Heap limit unavailable",
                graph.heapFraction ?: 0f,
                LocalAccentColors.current.warn,
                LocalAccentColors.current.neutralBg,
                muted = graph.heapFraction == null,
            )
            VisualBar(
                process.cpuPercent?.let { "CPU %.0f%%".format(it) } ?: "CPU unavailable",
                cpuFraction,
                LocalAccentColors.current.success,
                LocalAccentColors.current.successBg,
                muted = process.cpuPercent == null,
            )
        }
    }
}

@Composable
private fun VisualBar(
    label: String,
    fraction: Float,
    foreground: Color,
    background: Color,
    muted: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
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
        Text(
            label,
            modifier = Modifier.width(138.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProcessInspector(process: GradleProcess, modifier: Modifier = Modifier) {
    SectionCard("Process inspector", modifier) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                ProcessTypeIcon(process.type, size = 24.dp)
                Column {
                    Text("PID ${process.pid}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(process.type.displayLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            InspectorGrid(process)
            Text("Command line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.sm),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    process.commandLine,
                    modifier = Modifier.padding(Space.sm),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun InspectorGrid(process: GradleProcess) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InspectorRow("Project", process.projectPath?.substringAfterLast('/') ?: "unattributed")
        InspectorRow("RSS", "${process.rssMemoryMb} MB")
        InspectorRow("Heap limit", process.maxHeapMb?.let { "$it MB" } ?: "unavailable")
        InspectorRow("CPU", process.cpuPercent?.let { "%.0f%%".format(it) } ?: "unavailable")
        InspectorRow("GC", process.gc ?: "unavailable")
        InspectorRow("Status", process.status)
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
