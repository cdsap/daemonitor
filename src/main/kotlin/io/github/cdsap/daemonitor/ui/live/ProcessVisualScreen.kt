package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.domain.LiveMetricLabels
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.live.charts.OverallRssTimelineChart
import kotlinx.coroutines.delay

private const val VISUAL_PANEL_REFRESH_MS = 30_000L

@Composable
fun ProcessVisualScreen(state: LiveUiState) {
    val latestState by rememberUpdatedState(state)
    // While this tab is composed (active), keep a 30s-refreshed snapshot for the chart panels.
    var panelState by remember { mutableStateOf(state) }

    LaunchedEffect(state.isLoading, state.isEmpty) {
        panelState = latestState
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(VISUAL_PANEL_REFRESH_MS)
            if (!latestState.isLoading && !latestState.isEmpty) {
                panelState = latestState
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader("Process visualizer")
        if (panelState.isLoading) {
            EmptyState("Scanning for Gradle processes...", modifier = Modifier.weight(1f))
        } else if (panelState.isEmpty) {
            EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
        } else {
            VisualDashboard(panelState, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VisualDashboard(state: LiveUiState, modifier: Modifier = Modifier) {
    val chartData = remember(state.processes) { VisualChartModel.fromProcesses(state.processes) }
    val defaultPid = remember(state.processes) { state.processes.maxByOrNull { it.rssMemoryMb }?.pid }
    val liveSelectedPid = (state.detail as? DetailState.Selected)?.process?.pid
        ?.takeIf { pid -> state.processes.any { it.pid == pid } }
    var selectedPid by remember { mutableStateOf(liveSelectedPid ?: defaultPid) }
    var metricMode by remember { mutableStateOf(VisualMetricMode.MEMORY) }
    val selected = state.processes.firstOrNull { it.pid == selectedPid }
        ?: state.processes.maxByOrNull { it.rssMemoryMb }
        ?: state.processes.first()
    val processPids = state.processes.map { it.pid }
    LaunchedEffect(processPids) {
        if (selectedPid !in processPids) selectedPid = defaultPid
    }
    val windowEndMs = state.rssTimeline.lastOrNull()?.atMs
    val fullChart = remember(state.rssTimeline, state.processes, windowEndMs) {
        VisualChartModel.timelineChart(
            samples = state.rssTimeline,
            processes = state.processes,
            windowEndMs = windowEndMs,
            windowDurationMs = VisualChartModel.DEFAULT_TIMELINE_WINDOW_MS,
        )
    }
    val timelineChart = remember(fullChart, metricMode) {
        VisualChartModel.forMode(fullChart, metricMode)
    }
    val selectedHeapUsed = LiveMetricLabels.liveHeapUsedDetail(selected.liveHeap, selected.type)
    val selectedHeapLimit = selected.maxHeapMb?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_DETAIL
    val modes = VisualMetricMode.entries
    val headline = when (metricMode) {
        VisualMetricMode.MEMORY ->
            "Total ${chartData.totalRssMb} MB RSS · ${state.processes.size} processes"
        else ->
            "${metricMode.chartTitle} · ${state.processes.size} processes · selected PID ${selected.pid}"
    }

    Column(
        modifier = modifier
            .padding(start = Space.lg, end = Space.lg, bottom = Space.lg)
            .testTag("visual-dashboard"),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.testTag("visual-metric-mode")) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = metricMode == mode,
                        onClick = { metricMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.testTag("visual-mode-${mode.name.lowercase()}"),
                    ) {
                        Text(mode.label)
                    }
                }
            }
            Text(
                "PID ${selected.pid} · RSS ${selected.rssMemoryMb} MB · Heap $selectedHeapUsed · Limit $selectedHeapLimit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = Space.md)
                    .weight(1f, fill = false)
                    .testTag("visual-selected-vitals"),
            )
        }

        ProcessSelectionStrip(
            processes = state.processes.sortedByDescending { it.rssMemoryMb },
            selectedPid = selected.pid,
            onSelect = { selectedPid = it },
            modifier = Modifier.fillMaxWidth(),
        )

        OverallRssTimelineChart(
            chart = timelineChart,
            currentTotalRssMb = chartData.totalRssMb,
            selectedPid = selected.pid,
            onSelectProcess = { selectedPid = it },
            title = metricMode.chartTitle,
            hint = "${metricMode.hint} Click a series to show or hide it.",
            axisUnit = metricMode.axisUnit,
            headline = headline,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun ProcessSelectionStrip(
    processes: List<GradleProcess>,
    selectedPid: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .testTag("visual-process-strip"),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        processes.forEach { process ->
            val selected = process.pid == selectedPid
            val border = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Text(
                text = "${process.type.displayLabel()} · ${process.pid}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .border(1.dp, border, RoundedCornerShape(Radius.sm))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        RoundedCornerShape(Radius.sm),
                    )
                    .clickable { onSelect(process.pid) }
                    .padding(horizontal = Space.sm, vertical = Space.xs)
                    .testTag("visual-process-${process.pid}"),
            )
        }
    }
}
