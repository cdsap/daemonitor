package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.application.ProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.ProcessSample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VisualViewModel(
    private val processSamples: ProcessSampleRepository,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(VisualUiState())
    val state: StateFlow<VisualUiState> = _state.asStateFlow()

    private var latestLiveState: LiveUiState = LiveUiState()
    private var selectedPid: Long? = null
    private var loadGeneration = 0

    fun onLiveState(liveState: LiveUiState) {
        latestLiveState = liveState
        if (_state.value.selectedRange == VisualRange.LIVE) {
            publishLive(liveState)
        }
    }

    fun selectRange(range: VisualRange) {
        if (range == VisualRange.LIVE) {
            publishLive(latestLiveState, selectedRange = range)
            return
        }

        val generation = ++loadGeneration
        _state.value = _state.value.copy(selectedRange = range, isLoading = true, errorText = null)
        scope.launch {
            val now = clockMs()
            val fromMs = range.windowMs?.let { now - it } ?: 0L
            val result = runCatching {
                withContext(ioDispatcher) { processSamples.samplesInRange(fromMs, now) }
            }
            if (generation != loadGeneration) return@launch
            result
                .onSuccess { publishHistorical(range, it, now) }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        selectedRange = range,
                        isLoading = false,
                        isEmpty = true,
                        errorText = error.message ?: error::class.simpleName ?: "Could not load samples",
                        statusText = "Could not load samples",
                    )
                }
        }
    }

    fun selectProcess(pid: Long) {
        selectedPid = pid
        _state.value = _state.value.withSelection(pid)
    }

    private fun publishLive(liveState: LiveUiState, selectedRange: VisualRange = VisualRange.LIVE) {
        loadGeneration += 1
        val endMs = liveState.rssTimeline.lastOrNull()?.atMs
        val chart = VisualChartModel.timelineChart(
            samples = liveState.rssTimeline,
            processes = liveState.processes,
            windowEndMs = endMs,
            windowDurationMs = VisualChartModel.DEFAULT_TIMELINE_WINDOW_MS,
        )
        val nextPid = chooseSelectedPid(chart, selectedPid)
        selectedPid = nextPid
        _state.value = VisualUiState(
            selectedRange = selectedRange,
            chart = chart,
            selectedPid = nextPid,
            currentTotalRssMb = liveState.summary.totalRssMb,
            activeProcessCount = liveState.summary.activeProcessCount,
            activeProjectCount = liveState.summary.activeProjectCount,
            isLoading = liveState.isLoading,
            isEmpty = chart.points.isEmpty(),
            statusText = when {
                chart.points.isNotEmpty() -> lastSampleText(chart)
                liveState.isEmpty && !liveState.isLoading -> "No Gradle processes are running right now."
                else -> "Collecting live samples..."
            },
            errorText = liveState.pollError?.errorType,
        ).withSelection(nextPid)
    }

    private fun publishHistorical(range: VisualRange, samples: List<ProcessSample>, nowMs: Long) {
        val chart = VisualChartModel.timelineChart(
            samples = samples,
            range = range,
            liveProcesses = latestLiveState.processes,
            nowMs = nowMs,
        )
        val nextPid = chooseSelectedPid(chart, selectedPid)
        selectedPid = nextPid
        _state.value = VisualUiState(
            selectedRange = range,
            chart = chart,
            selectedPid = nextPid,
            currentTotalRssMb = chart.points.lastOrNull()?.valuesBySeriesId?.get(VisualChartModel.TOTAL_SERIES_ID) ?: 0,
            activeProcessCount = latestLiveState.summary.activeProcessCount,
            activeProjectCount = latestLiveState.summary.activeProjectCount,
            isLoading = false,
            isEmpty = chart.points.isEmpty(),
            statusText = if (chart.points.isEmpty()) {
                "No samples in this range"
            } else {
                "${samples.size} retained samples · ${lastSampleText(chart)}"
            },
            errorText = null,
        ).withSelection(nextPid)
    }

    private fun VisualUiState.withSelection(pid: Long?): VisualUiState {
        val summary = pid?.let { selectedSummary(chart, it) }
        return copy(selectedPid = summary?.pid ?: pid, selectedSummary = summary)
    }

    private fun selectedSummary(chart: RssTimelineChartData, pid: Long): SelectedProcessVisualSummary? {
        val rssId = VisualChartModel.rssSeriesId(pid)
        val heapId = VisualChartModel.heapSeriesId(pid)
        val rssValues = chart.points.mapNotNull { it.valuesBySeriesId[rssId] }
        if (rssValues.isEmpty()) return null
        val seriesLabel = chart.series.firstOrNull { it.id == rssId }?.label?.removeSuffix(" · RSS") ?: "PID $pid"
        return SelectedProcessVisualSummary(
            pid = pid,
            label = seriesLabel,
            currentRssMb = rssValues.last(),
            peakRssMb = rssValues.maxOrNull() ?: 0L,
            heapLimitMb = chart.points.asReversed().firstNotNullOfOrNull { point ->
                point.valuesBySeriesId[heapId]?.takeIf { it > 0 }
            },
            sampleCount = rssValues.count { it > 0 },
        )
    }

    private fun chooseSelectedPid(chart: RssTimelineChartData, preferredPid: Long?): Long? {
        val pids = chart.series.mapNotNull { it.pid }.distinct()
        if (preferredPid in pids) return preferredPid
        return pids.maxByOrNull { pid ->
            val rssId = VisualChartModel.rssSeriesId(pid)
            chart.points.maxOfOrNull { it.valuesBySeriesId[rssId] ?: 0L } ?: 0L
        }
    }

    private fun lastSampleText(chart: RssTimelineChartData): String? =
        chart.points.lastOrNull()?.let { "Last sample ${formatClock(it.atMs)}" }

    private fun formatClock(atMs: Long): String =
        SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(atMs))
}
