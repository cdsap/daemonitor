package io.github.cdsap.daemonitor.ui.history

import io.github.cdsap.daemonitor.domain.model.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable state the Historical screen renders (U8). */
data class HistoryUiState(
    val builds: List<Build> = emptyList(),
    val projects: List<String> = emptyList(),
    val filter: HistoryFilter = HistoryFilter(),
    val isEmptyResult: Boolean = false,
)

/**
 * Holds Historical state and applies filters live as they change (U8). Framework-light so the
 * project + time-range AND-filtering is unit-testable. The DB `Flow`s feed [onBuilds]/[onProjects];
 * the UI collects [state].
 */
class HistoryViewModel(private val now: () -> Long = System::currentTimeMillis) {
    private var allBuilds: List<Build> = emptyList()

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun onBuilds(builds: List<Build>) {
        allBuilds = builds
        recompute()
    }

    fun onProjects(projects: List<String>) {
        _state.value = _state.value.copy(projects = projects)
    }

    fun setProject(projectPath: String?) {
        _state.value = _state.value.copy(filter = _state.value.filter.copy(projectPath = projectPath))
        recompute()
    }

    fun setTimeRange(range: TimeRange) {
        _state.value = _state.value.copy(filter = _state.value.filter.copy(timeRange = range))
        recompute()
    }

    private fun recompute() {
        val filter = _state.value.filter
        val filtered = HistoryFilters.apply(allBuilds, filter.projectPath, filter.timeRange.cutoffMs(now()))
        _state.value = _state.value.copy(
            builds = filtered,
            isEmptyResult = filtered.isEmpty(),
        )
    }
}
