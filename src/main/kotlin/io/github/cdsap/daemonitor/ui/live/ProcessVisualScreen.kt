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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
fun ProcessVisualScreen(
    state: LiveUiState,
    visualState: VisualTimelineState = VisualTimelineState(),
    onTimeRange: (VisualTimeRange) -> Unit = {},
) {
    val latestState by rememberUpdatedState(state)
    // While this tab is composed (active), keep a 30s-refreshed snapshot for the chart panels.
    var panelState by remember { mutableStateOf(state) }
    val defaultPid = state.processes.maxByOrNull { it.rssMemoryMb }?.pid
    val liveSelectedPid = (state.detail as? DetailState.Selected)?.process?.pid
        ?.takeIf { pid -> state.processes.any { it.pid == pid } }
    var selectedPid by remember { mutableStateOf(liveSelectedPid ?: defaultPid) }

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
        TimeRangeControl(visualState.range, onTimeRange)
        when {
            visualState.range == VisualTimeRange.LIVE && panelState.isLoading ->
                EmptyState("Scanning for Gradle processes...", modifier = Modifier.weight(1f))
            visualState.range == VisualTimeRange.LIVE && panelState.isEmpty ->
                EmptyState("No Gradle processes are running right now.", modifier = Modifier.weight(1f))
            visualState.range != VisualTimeRange.LIVE && visualState.isLoading ->
                EmptyState("Loading retained process samples...", modifier = Modifier.weight(1f))
            visualState.range != VisualTimeRange.LIVE && visualState.samples.isEmpty() ->
                EmptyState("No retained process samples in the selected range.", modifier = Modifier.weight(1f))
            else -> {
                val samples = if (visualState.range == VisualTimeRange.LIVE) {
                    panelState.rssTimeline
                } else {
                    visualState.samples
                }
                VisualDashboard(
                    state = panelState,
                    samples = samples,
                    range = visualState.range,
                    selectedPid = selectedPid,
                    onSelectProcess = { selectedPid = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TimeRangeControl(selected: VisualTimeRange, onTimeRange: (VisualTimeRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.lg, bottom = Space.md)
            .testTag("visual-time-range"),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("RANGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VisualTimeRange.entries.forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onTimeRange(range) },
                label = { Text(range.label) },
                shape = RoundedCornerShape(Radius.sm),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.testTag("visual-range-${range.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun VisualDashboard(
    state: LiveUiState,
    samples: List<RssTimelineSample>,
    range: VisualTimeRange,
    selectedPid: Long?,
    onSelectProcess: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chartData = remember(state.processes) { VisualChartModel.fromProcesses(state.processes) }
    val sampledPids = remember(samples) { samples.flatMap { it.byPid.keys }.toSet() }
    val chartProcesses = if (range == VisualTimeRange.LIVE) {
        state.processes
    } else {
        state.processes.filter { it.pid in sampledPids }
    }
    val availablePids = remember(sampledPids, chartProcesses) {
        sampledPids + chartProcesses.map { it.pid }
    }
    val defaultPid = remember(samples, state.processes) {
        state.processes.maxByOrNull { it.rssMemoryMb }?.pid
            ?: samples.firstOrNull()?.byPid?.keys?.firstOrNull()
    }
    LaunchedEffect(availablePids, defaultPid) {
        if (selectedPid !in availablePids) defaultPid?.let(onSelectProcess)
    }
    var metricMode by remember { mutableStateOf(VisualMetricMode.MEMORY) }
    val selected = state.processes.firstOrNull { it.pid == selectedPid }
    val resolvedPid = selected?.pid ?: selectedPid ?: defaultPid
    val windowEndMs = samples.lastOrNull()?.atMs
    val windowDurationMs = range.durationMs ?: VisualChartModel.DEFAULT_TIMELINE_WINDOW_MS
    val fullChart = remember(samples, chartProcesses, windowEndMs, windowDurationMs, range) {
        VisualChartModel.timelineChart(
            samples = samples,
            processes = chartProcesses,
            windowEndMs = windowEndMs,
            windowDurationMs = windowDurationMs,
            useLiveValueFallbacks = range == VisualTimeRange.LIVE,
        )
    }
    val timelineChart = remember(fullChart, metricMode) {
        VisualChartModel.forMode(fullChart, metricMode)
    }
    val selectedHeapUsed = if (range == VisualTimeRange.LIVE) {
        selected?.let { LiveMetricLabels.liveHeapUsedDetail(it.liveHeap, it.type) }
    } else {
        selectedPid?.let { pid -> samples.mapNotNull { it.heapUsedByPid[pid] }.lastOrNull() }?.let { "$it MB" }
    } ?: LiveMetricLabels.HEAP_UNAVAILABLE_DETAIL
    val selectedHeapLimit = if (range == VisualTimeRange.LIVE) {
        selected?.maxHeapMb
    } else {
        selectedPid?.let { pid -> samples.mapNotNull { it.heapLimitByPid[pid] }.lastOrNull() }
    }?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_DETAIL
    val latestTotalRssMb = samples.lastOrNull()?.totalRssMb ?: chartData.totalRssMb
    val modes = VisualMetricMode.entries
    val selectedRss = selected?.rssMemoryMb
        ?: selectedPid?.let { pid -> samples.mapNotNull { it.byPid[pid] }.lastOrNull() }
    val headline = when (metricMode) {
        VisualMetricMode.MEMORY ->
            "Total $latestTotalRssMb MB RSS · ${availablePids.size} processes"
        else ->
            "${metricMode.chartTitle} · ${availablePids.size} processes" +
                (resolvedPid?.let { " · selected PID $it" } ?: "")
    }
    val vitals = buildString {
        append("PID ${resolvedPid ?: "—"}")
        append(" · RSS ${selectedRss?.let { "$it MB" } ?: "—"}")
        append(" · Heap $selectedHeapUsed")
        append(" · Limit $selectedHeapLimit")
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
                vitals,
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

        if (chartProcesses.isNotEmpty()) {
            ProcessSelectionStrip(
                processes = chartProcesses.sortedByDescending { it.rssMemoryMb },
                selectedPid = resolvedPid,
                onSelect = onSelectProcess,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OverallRssTimelineChart(
            chart = timelineChart,
            currentTotalRssMb = latestTotalRssMb,
            selectedPid = resolvedPid,
            onSelectProcess = onSelectProcess,
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
    selectedPid: Long?,
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
