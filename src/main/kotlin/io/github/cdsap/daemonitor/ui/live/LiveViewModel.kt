package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds and transitions Live Monitor state (U7). Framework-light (no Compose dependency) so the
 * selection/summary logic is unit-testable. The poll loop in [io.github.cdsap.daemonitor.WatcherService]
 * calls [onPoll]; the UI collects [state].
 */
class LiveViewModel {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    /** Apply a fresh poll result. Handles the selected→ended transition when a PID disappears. */
    fun onPoll(processes: List<GradleProcess>, tailForSelected: List<String> = emptyList()) {
        val current = _state.value
        val detail = nextDetail(current.detail, processes)
        _state.value = current.copy(
            processes = processes,
            summary = summarize(processes),
            detail = detail,
            tail = if (detail is DetailState.Ended) current.tail else tailForSelected,
            isEmpty = processes.isEmpty(),
        )
    }

    fun select(pid: Long) {
        val process = _state.value.processes.firstOrNull { it.pid == pid } ?: return
        _state.value = _state.value.copy(detail = DetailState.Selected(process))
    }

    fun clearSelection() {
        _state.value = _state.value.copy(detail = DetailState.NoSelection)
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
}
