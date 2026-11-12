package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.cdsap.daemonitor.domain.LiveMetricLabels
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.common.StatTile
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
    var selectedPid by remember { mutableStateOf(state.processes.maxByOrNull { it.rssMemoryMb }?.pid) }

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
        modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.md),
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
        state.processes.maxByOrNull { it.rssMemoryMb }?.pid ?: samples.firstOrNull()?.byPid?.keys?.firstOrNull()
    }
    LaunchedEffect(availablePids, defaultPid) {
        if (selectedPid !in availablePids) defaultPid?.let(onSelectProcess)
    }
    val selected = state.processes.firstOrNull { it.pid == selectedPid }
    val windowEndMs = samples.lastOrNull()?.atMs
    val windowDurationMs = range.durationMs ?: VisualChartModel.DEFAULT_TIMELINE_WINDOW_MS
    val timelineChart = remember(samples, chartProcesses, windowEndMs, windowDurationMs) {
        VisualChartModel.timelineChart(
            samples = samples,
            processes = chartProcesses,
            windowEndMs = windowEndMs,
            windowDurationMs = windowDurationMs,
            useLiveValueFallbacks = range == VisualTimeRange.LIVE,
        )
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

    Column(modifier = modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatTile("Processes", availablePids.size.toString(), Modifier.weight(1f), icon = Icons.Filled.Memory)
            StatTile("RSS total", "$latestTotalRssMb MB", Modifier.weight(1f), icon = Icons.Filled.Storage, accent = LocalAccentColors.current.info)
            StatTile("Selected heap used", selectedHeapUsed, Modifier.weight(1f), icon = Icons.Filled.Timeline, accent = LocalAccentColors.current.warn)
            StatTile("Selected heap limit", selectedHeapLimit, Modifier.weight(1f), accent = LocalAccentColors.current.brand)
        }

        OverallRssTimelineChart(
            chart = timelineChart,
            currentTotalRssMb = latestTotalRssMb,
            selectedPid = selectedPid,
            onSelectProcess = onSelectProcess,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
