package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import io.github.cdsap.daemonitor.domain.LiveMetricLabels
import io.github.cdsap.daemonitor.domain.model.GradleProcess
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
import io.github.cdsap.daemonitor.ui.common.cycleIndex

private val COLS = listOf(
    Col("Type", 1.2f),
    Col("Project", 1.5f),
    Col("PID", 0.55f, end = true),
    Col("RSS", 0.75f, end = true),
    Col("Heap used", 0.85f, end = true),
    Col("YGCT (s)", 0.8f, end = true),
    Col("FGCT (s)", 0.8f, end = true),
    Col("CGCT (s)", 0.8f, end = true),
    Col("GCT (s)", 0.8f, end = true),
    Col("CPU", 0.55f, end = true),
    Col("Uptime", 0.85f, end = true),
    Col("Flags", 1.4f),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LiveMonitorScreen(state: LiveUiState, onSelect: (Long) -> Unit, onClearSelection: () -> Unit) {
    var groupedView by remember { mutableStateOf(false) }
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
        SummaryHeader(
            state,
            groupedView = groupedView,
            onGroupedViewChange = { groupedView = it },
            onSelectPeakMemoryPid = onSelect,
        )
        if (state.isLoading) {
            EmptyState("Scanning for Gradle processes...", modifier = Modifier.weight(1f))
        } else if (state.isEmpty) {
            EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
        } else {
            val concurrent = Badges.concurrentSameProjectPids(state.processes)
            val displayRows = if (groupedView) {
                groupedLiveProcessRows(state.processes)
            } else {
                state.processes.map { LiveProcessRow(it) }
            }
            val listFocus = remember { FocusRequester() }
            // Request once when the table appears — not on every poll, which would steal focus.
            LaunchedEffect(Unit) { listFocus.requestFocus() }
            fun moveSelection(delta: Int) {
                val currentIndex = selectedPid?.let { pid -> displayRows.indexOfFirst { it.targetPid == pid } }
                    ?.takeIf { it >= 0 }
                val next = cycleIndex(displayRows.size, currentIndex, delta) ?: return
                onSelect(displayRows[next].targetPid)
            }
            Row(modifier = Modifier.weight(1f).padding(Space.lg)) {
                Surface(
                    modifier = Modifier.weight(1.6f).fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(listFocus)
                            .focusable()
                            .testTag("live-process-list")
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> { moveSelection(1); true }
                                    Key.DirectionUp -> { moveSelection(-1); true }
                                    else -> false
                                }
                            },
                    ) {
                        TableHeader(COLS)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(displayRows, key = { it.targetPid }) { row ->
                                ProcessRow(
                                    row,
                                    row.targetPid == selectedPid,
                                    row.process.pid in concurrent,
                                    nowMs,
                                    state::isPermissionDegraded,
                                ) { pid ->
                                    onSelect(pid)
                                    listFocus.requestFocus()
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(Space.lg))
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    DetailCard(state.detail, state.processes, nowMs, modifier = Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.padding(Space.xs))
                    LogCard(state.tailState, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        PrivacyNotice()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SummaryHeader(
    state: LiveUiState,
    groupedView: Boolean,
    onGroupedViewChange: (Boolean) -> Unit,
    onSelectPeakMemoryPid: (Long) -> Unit,
) {
    Column {
        ScreenHeader("Process monitor") {
            val degraded = state.pollError != null
            val statusColor = if (degraded) LocalAccentColors.current.warn else LocalAccentColors.current.success
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.size(7.dp).background(statusColor, androidx.compose.foundation.shape.CircleShape),
                )
                Text(
                    if (degraded) "DEGRADED" else "MONITORING",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                SingleChoiceSegmentedButtonRow {
                    listOf("Flat" to false, "Grouped" to true).forEachIndexed { index, (label, grouped) ->
                        SegmentedButton(
                            selected = groupedView == grouped,
                            onClick = { onGroupedViewChange(grouped) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            icon = {},
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatTile("Processes", state.summary.activeProcessCount.toString(), Modifier.weight(1f), icon = Icons.Filled.Memory)
            StatTile("Resident memory", "${state.summary.totalRssMb} MB", Modifier.weight(1f), icon = Icons.Filled.Storage, accent = LocalAccentColors.current.info)
            val peakPid = state.summary.highestMemoryPid
            StatTile(
                label = "Peak memory PID",
                value = peakPid?.toString() ?: "—",
                modifier = Modifier.weight(1f).testTag("peak-memory-pid-tile"),
                icon = Icons.Filled.TrendingUp,
                accent = LocalAccentColors.current.warn,
                onClick = peakPid?.let { pid -> { onSelectPeakMemoryPid(pid) } },
            )
            StatTile("Projects", state.summary.activeProjectCount.toString(), Modifier.weight(1f), icon = Icons.Filled.FolderOpen, accent = LocalAccentColors.current.brand)
        }
    }
}

@Composable
private fun ProcessRow(
    row: LiveProcessRow,
    selected: Boolean,
    concurrent: Boolean,
    nowMs: Long,
    isDegraded: (GradleProcess) -> Boolean,
    onSelect: (Long) -> Unit,
) {
    val p = row.process
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
                if (row.depth > 0 || row.isGroupRoot) {
                    Text(if (row.isGroupRoot) "▾" else "└", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.depth > 0) Spacer(Modifier.width((row.depth * 12).dp))
                ProcessTypeIcon(p.type, size = 16.dp)
                Text(p.type.displayLabel(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Cell(p.projectPath?.substringAfterLast('/') ?: "—", COLS[1], muted = p.projectPath == null)
        Cell(p.pid.toString(), COLS[2])
        Cell("${p.rssMemoryMb} MB", COLS[3])
        Cell(LiveMetricLabels.liveHeapUsedCompact(p.liveHeap), COLS[4], muted = p.liveHeap?.available != true)
        Cell(p.youngGcTimeSeconds.gcTimeCompact(), COLS[5], muted = p.youngGcTimeSeconds == null)
        Cell(p.fullGcTimeSeconds.gcTimeCompact(), COLS[6], muted = p.fullGcTimeSeconds == null)
        Cell(p.concurrentGcTimeSeconds.gcTimeCompact(), COLS[7], muted = p.concurrentGcTimeSeconds == null)
        Cell(p.totalGcTimeSeconds.gcTimeCompact(), COLS[8], muted = p.totalGcTimeSeconds == null)
        Cell(LiveMetricLabels.cpuCompact(p.cpuPercent), COLS[9], muted = p.cpuPercent == null)
        Cell(formatUptime(p.startTimeMs, nowMs), COLS[10])
        Row(modifier = Modifier.weight(COLS[11].weight), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Badges.memoryBadge(p.rssMemoryMb)?.let { MemoryBadge(it) }
            if (concurrent) ConcurrentBadge()
            if (p.automated) AutomatedBadge()
            if (isDegraded(p)) Text("🔒", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailCard(
    detail: DetailState,
    processes: List<GradleProcess>,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    SectionCard("Process detail", modifier) {
        when (detail) {
            is DetailState.NoSelection ->
                Text("Select a process to see details.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            is DetailState.Selected -> ProcessDetails(
                detail.process,
                children = processes.filter { it.parentPid == detail.process.pid },
                ended = false,
                nowMs = nowMs,
            )
            is DetailState.Ended -> ProcessDetails(detail.lastKnown, children = emptyList(), ended = true, nowMs = nowMs)
        }
    }
}

@Composable
private fun ProcessDetails(
    p: GradleProcess,
    children: List<GradleProcess>,
    ended: Boolean,
    nowMs: Long,
) {
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
            Text("PID ${p.pid} · ${p.type.displayLabel()}", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.padding(Space.xs))
        if (children.isNotEmpty()) {
            DetailSection("CHILD PROCESSES")
            Column(modifier = Modifier.testTag("child-processes")) {
                children.forEach { child -> ChildProcessRow(child) }
            }
        }
        DetailSection("Memory")
        DetailRow("RSS", "${p.rssMemoryMb} MB")
        DetailRow("Virtual memory", p.virtualMemoryMb.metric("MB"))
        DetailRow("Swap", p.swapMemoryMb.metric("MB"))
        DetailRow("Heap used", LiveMetricLabels.liveHeapUsedDetail(p.liveHeap, p.type))
        DetailRow(
            "Heap committed",
            p.liveHeap?.takeIf { it.available }?.committedMb?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_DETAIL,
        )
        DetailRow("Heap limit (-Xmx)", p.maxHeapMb?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_DETAIL)
        DetailRow(
            "Heap max (runtime)",
            p.liveHeap?.takeIf { it.available }?.maxMb?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_DETAIL,
        )
        DetailSection("CPU")
        DetailRow("CPU", LiveMetricLabels.cpuDetail(p.cpuPercent))
        DetailRow("Active processors", p.activeProcessorCount.metric())

        DetailSection("JVM")
        DetailRow("Java", listOfNotNull(p.javaVersion, p.javaVendor).joinToString(" · ").ifBlank { "unavailable" })
        DetailRow("Java runtime", p.javaRuntimeVersion ?: "unavailable")
        DetailRow("Java VM", p.javaVmName ?: "unavailable")
        DetailRow("Java VM version", p.javaVmVersion ?: "unavailable")
        DetailRow("OS", p.osName ?: "unavailable")
        DetailRow("OS arch", p.osArch ?: "unavailable")
        DetailRow("GC", p.gc ?: "unavailable")
        DetailRow("Metaspace used", p.metaspaceUsedMb.metric("MB"))
        DetailRow("Metaspace committed", p.metaspaceCommittedMb.metric("MB"))

        DetailSection("GC")
        DetailRow("Young GC", p.youngGcCount.metric("collections"))
        DetailRow("Young GC time", p.youngGcTimeMs.metric("ms"))
        DetailRow("YGCT", p.youngGcTimeSeconds.gcTimeDetail())
        DetailRow("FGCT", p.fullGcTimeSeconds.gcTimeDetail())
        DetailRow("CGCT", p.concurrentGcTimeSeconds.gcTimeDetail())
        DetailRow("GCT", p.totalGcTimeSeconds.gcTimeDetail())
        DetailRow("Old GC", p.oldGcCount.metric("collections"))
        DetailRow("Old GC time", p.oldGcTimeMs.metric("ms"))

        DetailSection("I/O")
        DetailRow("Read", p.readBytes.metricBytes())
        DetailRow("Write", p.writeBytes.metricBytes())
        DetailRow("Read operations", p.readOperations.metric())
        DetailRow("Write operations", p.writeOperations.metric())
        DetailRow("Minor page faults", p.minorPageFaults.metric())
        DetailRow("Major page faults", p.majorPageFaults.metric())

        DetailSection("Process")
        DetailRow("Threads", p.threadCount.metric())
        DetailRow("Open file descriptors", p.openFileDescriptors.metric())
        DetailRow("Context switches", listOfNotNull(
            p.voluntaryContextSwitches?.let { "voluntary $it" },
            p.involuntaryContextSwitches?.let { "involuntary $it" },
        ).ifEmpty { listOf("unavailable") }.joinToString(", "))
        DetailRow("Uptime", if (ended) "—" else formatUptime(p.startTimeMs, nowMs))
        DetailRow("Working dir", p.workingDirectory ?: "unavailable")
        Spacer(Modifier.padding(Space.xs))
        Text("Command line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.commandLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChildProcessRow(child: GradleProcess) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            "${child.type.displayLabel()} · PID ${child.pid}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            "RSS ${child.rssMemoryMb} MB · CPU ${LiveMetricLabels.cpuCompact(child.cpuPercent)} · ${child.status.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            "${if (child.projectPath != null) "Project" else "Work dir"} ${child.projectPath ?: child.workingDirectory ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            "Heap ${LiveMetricLabels.liveHeapUsedCompact(child.liveHeap)} · GC ${child.gc ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailSection(label: String) {
    Text(
        label,
        modifier = Modifier.padding(top = Space.sm, bottom = Space.xs),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun Long?.metric(unit: String = ""): String = this?.let { if (unit.isBlank()) it.toString() else "$it $unit" } ?: "unavailable"

private fun Double?.gcTimeDetail(): String = this?.let { String.format(Locale.ROOT, "%.3f s", it) } ?: "—"

private fun Double?.gcTimeCompact(): String = this?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"

private fun Long?.metricBytes(): String = this?.let {
    when {
        it >= 1024L * 1024L -> "%.1f MB".format(it / (1024.0 * 1024.0))
        it >= 1024L -> "%.1f KB".format(it / 1024.0)
        else -> "$it B"
    }
} ?: "unavailable"

@Composable
private fun LogCard(tailState: LogTailState, modifier: Modifier = Modifier) {
    SectionCard("Daemon log", modifier) {
        when (tailState) {
            LogTailState.NoSelection -> Text("Select a process to see its daemon log.", modifier = Modifier.padding(Space.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LogTailState.Loading -> Text("Loading daemon log…", modifier = Modifier.padding(Space.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LogTailState.NoLog -> Text("No daemon log was found for this process.", modifier = Modifier.padding(Space.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
            is LogTailState.Error -> Text("Daemon log unavailable (${tailState.errorType}).", modifier = Modifier.padding(Space.md), color = LocalAccentColors.current.warn)
            is LogTailState.Available -> LogView(tailState.lines, modifier = Modifier.fillMaxSize(), autoScroll = true)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
    }
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
