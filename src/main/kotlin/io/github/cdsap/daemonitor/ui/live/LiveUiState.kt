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

/** Safe poll diagnostic. Exception messages are deliberately excluded because they may contain
 * command lines, paths, or daemon-log content. */
data class PollError(
    val failedAtMs: Long,
    val errorType: String,
)

/** One poll-time sample for the Visual tab memory timeline. */
data class RssTimelineSample(
    val atMs: Long,
    val totalRssMb: Long,
    val byPid: Map<Long, Long> = emptyMap(),
    /** Configured max heap (-Xmx) per PID when recoverable; live heap occupancy is unavailable. */
    val heapByPid: Map<Long, Long> = emptyMap(),
)

/** Full immutable state the Live Monitor renders. */
data class LiveUiState(
    val processes: List<GradleProcess> = emptyList(),
    val summary: LiveSummary = LiveSummary(0, 0, null, 0),
    val detail: DetailState = DetailState.NoSelection,
    val tail: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = true,
    val pollError: PollError? = null,
    val rssTimeline: List<RssTimelineSample> = emptyList(),
) {
    /** A process row whose restricted fields (cwd/project) could not be read (KTD-6). */
    fun isPermissionDegraded(p: GradleProcess): Boolean = p.workingDirectory == null
}
