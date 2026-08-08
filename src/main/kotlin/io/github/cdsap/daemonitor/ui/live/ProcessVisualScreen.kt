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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.common.StatTile
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
    var selectedPid by remember(state.processes) { mutableStateOf(defaultPid) }
    val selected = state.processes.firstOrNull { it.pid == selectedPid } ?: state.processes.first()
    val windowEndMs = state.rssTimeline.lastOrNull()?.atMs
    val timelineChart = remember(state.rssTimeline, state.processes, windowEndMs) {
        VisualChartModel.timelineChart(
            samples = state.rssTimeline,
            processes = state.processes,
            windowEndMs = windowEndMs,
            windowDurationMs = VisualChartModel.DEFAULT_TIMELINE_WINDOW_MS,
        )
    }
    val selectedHeap = selected.maxHeapMb?.let { "$it MB" } ?: "unavailable"

    Column(modifier = modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatTile("Processes", state.summary.activeProcessCount.toString(), Modifier.weight(1f), icon = Icons.Filled.Memory)
            StatTile("RSS total", "${chartData.totalRssMb} MB", Modifier.weight(1f), icon = Icons.Filled.Storage, accent = LocalAccentColors.current.info)
            StatTile("Selected heap", selectedHeap, Modifier.weight(1f), icon = Icons.Filled.Timeline, accent = LocalAccentColors.current.warn)
            StatTile("Projects", state.summary.activeProjectCount.toString(), Modifier.weight(1f), accent = LocalAccentColors.current.brand)
        }

        OverallRssTimelineChart(
            chart = timelineChart,
            currentTotalRssMb = chartData.totalRssMb,
            selectedPid = selected.pid,
            onSelectProcess = { selectedPid = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
