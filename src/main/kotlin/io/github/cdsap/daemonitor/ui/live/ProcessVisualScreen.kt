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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.common.StatTile
import io.github.cdsap.daemonitor.ui.live.charts.OverallRssTimelineChart

@Composable
fun ProcessVisualScreen(
    state: LiveUiState,
    visualState: VisualUiState? = null,
    onRange: (VisualRange) -> Unit = {},
    onSelectProcess: (Long) -> Unit = {},
) {
    var localPanelState by remember(state) { mutableStateOf(state.toVisualUiState()) }
    val panelState = visualState ?: localPanelState
    val selectProcess: (Long) -> Unit = { pid ->
        if (visualState == null) {
            localPanelState = localPanelState.withProcessSelection(pid, state.processes)
        } else {
            onSelectProcess(pid)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader("Process visualizer")
        if (panelState.isLoading) {
            EmptyState("Scanning for Gradle processes...", modifier = Modifier.weight(1f))
        } else if (panelState.isEmpty) {
            Column(modifier = Modifier.weight(1f).padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
                VisualRangeHeader(panelState.selectedRange, onRange)
                EmptyState(panelState.statusText ?: "No samples in this range", modifier = Modifier.weight(1f))
            }
        } else {
            VisualDashboard(
                state = panelState,
                onRange = onRange,
                onSelectProcess = selectProcess,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VisualDashboard(
    state: VisualUiState,
    onRange: (VisualRange) -> Unit,
    onSelectProcess: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedSummary
    val selectedHeap = selected?.heapLimitMb?.let { "$it MB" } ?: "unavailable"
    Column(modifier = modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
        VisualRangeHeader(state.selectedRange, onRange)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatTile("Processes", state.activeProcessCount.toString(), Modifier.weight(1f), icon = Icons.Filled.Memory)
            StatTile("RSS total", "${state.currentTotalRssMb} MB", Modifier.weight(1f), icon = Icons.Filled.Storage, accent = LocalAccentColors.current.info)
            StatTile("Series", state.chart.series.size.toString(), Modifier.weight(1f), icon = Icons.Filled.Timeline, accent = LocalAccentColors.current.warn)
            StatTile("Projects", state.activeProjectCount.toString(), Modifier.weight(1f), accent = LocalAccentColors.current.brand)
        }
        selected?.let { summary ->
            Text(
                summary.label,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.xs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                StatTile("Current RSS", "${summary.currentRssMb} MB", Modifier.weight(1f), accent = LocalAccentColors.current.info)
                StatTile("Peak RSS", "${summary.peakRssMb} MB", Modifier.weight(1f), accent = LocalAccentColors.current.danger)
                StatTile("Heap limit", selectedHeap, Modifier.weight(1f), accent = LocalAccentColors.current.warn)
                StatTile("Samples", summary.sampleCount.toString(), Modifier.weight(1f), accent = LocalAccentColors.current.success)
            }
        }

        OverallRssTimelineChart(
            chart = state.chart,
            currentTotalRssMb = state.currentTotalRssMb,
            selectedPid = state.selectedPid,
            rangeLabel = state.selectedRange.label,
            statusText = state.statusText,
            onSelectProcess = onSelectProcess,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun VisualRangeHeader(selectedRange: VisualRange, onRange: (VisualRange) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.md),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SingleChoiceSegmentedButtonRow {
            VisualRange.entries.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = selectedRange == range,
                    onClick = { onRange(range) },
                    shape = SegmentedButtonDefaults.itemShape(index, VisualRange.entries.size),
                ) {
                    Text(range.label)
                }
            }
        }
    }
}

private fun LiveUiState.toVisualUiState(): VisualUiState {
    val endMs = rssTimeline.lastOrNull()?.atMs
    val chart = VisualChartModel.timelineChart(
        samples = rssTimeline,
        processes = processes,
        windowEndMs = endMs,
        windowDurationMs = VisualChartModel.DEFAULT_TIMELINE_WINDOW_MS,
    )
    val selectedPid = summary.highestMemoryPid ?: chart.series.firstOrNull { it.pid != null }?.pid
    return VisualUiState(
        selectedRange = VisualRange.LIVE,
        chart = chart,
        selectedPid = selectedPid,
        currentTotalRssMb = summary.totalRssMb,
        activeProcessCount = summary.activeProcessCount,
        activeProjectCount = summary.activeProjectCount,
        isLoading = isLoading,
        isEmpty = chart.points.isEmpty(),
        statusText = when {
            chart.points.isNotEmpty() -> "Last sample available"
            isEmpty && !isLoading -> "No Gradle processes are running right now."
            else -> "Collecting live samples..."
        },
        errorText = pollError?.errorType,
    ).withProcessSelection(selectedPid, processes)
}

private fun VisualUiState.withProcessSelection(pid: Long?, processes: List<GradleProcess>): VisualUiState {
    val summary = pid?.let { chart.selectedSummary(it, processes) }
    return copy(selectedPid = summary?.pid ?: pid, selectedSummary = summary)
}

private fun RssTimelineChartData.selectedSummary(
    pid: Long,
    processes: List<GradleProcess>,
): SelectedProcessVisualSummary? {
    val selectedProcess = processes.firstOrNull { it.pid == pid }
    val rssValues = points.mapNotNull { it.valuesBySeriesId[VisualChartModel.rssSeriesId(pid)] }
    if (rssValues.isEmpty()) return null
    return SelectedProcessVisualSummary(
        pid = pid,
        label = selectedProcess?.let { process ->
            val project = process.projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            listOfNotNull(process.type.displayLabel(), project, "PID ${process.pid}").joinToString(" · ")
        } ?: "PID $pid",
        currentRssMb = rssValues.last(),
        peakRssMb = rssValues.maxOrNull() ?: 0,
        heapLimitMb = selectedProcess?.maxHeapMb,
        sampleCount = rssValues.count { it > 0 },
    )
}
