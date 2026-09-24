package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.application.DaemonLogTailResult
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds and transitions Live Monitor state (U7). Framework-light (no Compose dependency) so the
 * selection/summary logic is unit-testable. The poll loop in [io.github.cdsap.daemonitor.WatcherService]
 * calls [onPoll]; the UI collects [state].
 */
class LiveViewModel(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val timelineCapacity: Int = DEFAULT_TIMELINE_CAPACITY,
) {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    /** Apply a fresh poll result. Handles the selected→ended transition when a PID disappears. */
    fun onPoll(processes: List<GradleProcess>, tailForSelected: DaemonLogTailResult? = null) {
        val current = _state.value
        val detail = nextDetail(current.detail, processes)
        val sample = RssTimelineSample(
            atMs = clockMs(),
            totalRssMb = processes.sumOf { it.rssMemoryMb },
            byPid = processes.associate { it.pid to it.rssMemoryMb },
            heapLimitByPid = processes.mapNotNull { process ->
                process.maxHeapMb?.let { heapMb -> process.pid to heapMb }
            }.toMap(),
            heapUsedByPid = processes.mapNotNull { process ->
                process.liveHeap?.takeIf { it.available }?.usedMb?.let { used -> process.pid to used }
            }.toMap(),
        )
        val nextTailState = when {
            // Keep a completed tail for an ended process; drop in-flight Loading so the panel
            // cannot stick on "Loading…" after the async select fetch is ignored for Ended.
            detail is DetailState.Ended -> when (current.tailState) {
                LogTailState.Loading -> LogTailState.NoLog
                else -> current.tailState
            }
            detail is DetailState.NoSelection -> LogTailState.NoSelection
            tailForSelected != null -> tailForSelected.toUiState()
            else -> current.tailState
        }
        _state.value = current.copy(
            processes = processes,
            summary = summarize(processes),
            detail = detail,
            tail = when (nextTailState) {
                is LogTailState.Available -> nextTailState.lines
                else -> emptyList()
            },
            tailState = nextTailState,
            isLoading = false,
            isEmpty = processes.isEmpty(),
            pollError = null,
            rssTimeline = (current.rssTimeline + sample).takeLast(timelineCapacity),
        )
    }

    /** Mark the current data stale while retaining the last successful snapshot. */
    fun onPollFailure(failedAtMs: Long, errorType: String) {
        _state.value = _state.value.copy(pollError = PollError(failedAtMs, errorType))
    }

    fun select(pid: Long) {
        val process = _state.value.processes.firstOrNull { it.pid == pid } ?: return
        _state.value = _state.value.copy(
            detail = DetailState.Selected(process),
            tail = emptyList(),
            tailState = LogTailState.Loading,
        )
    }

    fun onTail(pid: Long, result: DaemonLogTailResult) {
        val detail = _state.value.detail
        if (detail !is DetailState.Selected || detail.process.pid != pid) return
        val tailState = result.toUiState()
        _state.value = _state.value.copy(
            tail = (tailState as? LogTailState.Available)?.lines.orEmpty(),
            tailState = tailState,
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(
            detail = DetailState.NoSelection,
            tail = emptyList(),
            tailState = LogTailState.NoSelection,
        )
    }

    private fun DaemonLogTailResult.toUiState(): LogTailState = when (this) {
        DaemonLogTailResult.NoLog -> LogTailState.NoLog
        is DaemonLogTailResult.Lines -> if (lines.isEmpty()) LogTailState.NoLog else LogTailState.Available(lines)
        is DaemonLogTailResult.Error -> LogTailState.Error(errorType)
    }

    private fun nextDetail(current: DetailState, processes: List<GradleProcess>): DetailState =
        when (current) {
            is DetailState.NoSelection -> DetailState.NoSelection
            is DetailState.Selected -> {
                val live = processes.firstOrNull { it.pid == current.process.pid }
                if (live != null) DetailState.Selected(live) else DetailState.Ended(current.process)
            }
            is DetailState.Ended -> current
        }

    private fun summarize(processes: List<GradleProcess>): LiveSummary {
        val highest = processes.maxByOrNull { it.rssMemoryMb }
        return LiveSummary(
            activeProcessCount = processes.size,
            totalRssMb = processes.sumOf { it.rssMemoryMb },
            highestMemoryPid = highest?.pid,
            activeProjectCount = processes.mapNotNull { it.projectPath }.distinct().size,
        )
    }

    companion object {
        const val DEFAULT_TIMELINE_CAPACITY = 90
    }
}
