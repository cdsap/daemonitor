package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Detail-panel state for the Live Monitor (U7). Three explicit states resolve the review gap. */
sealed interface DetailState {
    /** Processes present but none selected — show a prompt, not a collapsed/blank panel. */
    data object NoSelection : DetailState

    /** A live process is selected. */
    data class Selected(val process: GradleProcess) : DetailState

    /** The selected process exited; show its last-known snapshot, not a frozen live view. */
    data class Ended(val lastKnown: GradleProcess) : DetailState
}

/** Header summary metrics for the Live Monitor (U7). */
data class LiveSummary(
    val activeProcessCount: Int,
    val totalRssMb: Long,
    val highestMemoryPid: Long?,
    val activeProjectCount: Int,
)

/** Full immutable state the Live Monitor renders. */
data class LiveUiState(
    val processes: List<GradleProcess> = emptyList(),
    val summary: LiveSummary = LiveSummary(0, 0, null, 0),
    val detail: DetailState = DetailState.NoSelection,
    val tail: List<String> = emptyList(),
    val isEmpty: Boolean = true,
) {
    /** A process row whose restricted fields (cwd/project) could not be read (KTD-6). */
    fun isPermissionDegraded(p: GradleProcess): Boolean = p.workingDirectory == null
}
