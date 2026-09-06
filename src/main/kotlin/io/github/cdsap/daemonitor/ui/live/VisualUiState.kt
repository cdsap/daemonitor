package io.github.cdsap.daemonitor.ui.live

enum class VisualRange(
    val label: String,
    val windowMs: Long?,
    val bucketMs: Long?,
) {
    LIVE("Live", null, null),
    FIFTEEN_MINUTES("15 min", 15 * 60 * 1000L, 10 * 1000L),
    ONE_HOUR("1 hour", 60 * 60 * 1000L, 30 * 1000L),
    ALL_RETAINED("All retained", null, null),
}

data class SelectedProcessVisualSummary(
    val pid: Long,
    val label: String,
    val currentRssMb: Long,
    val peakRssMb: Long,
    val heapLimitMb: Long?,
    val sampleCount: Int,
)

data class VisualUiState(
    val selectedRange: VisualRange = VisualRange.LIVE,
    val chart: RssTimelineChartData = RssTimelineChartData(emptyList(), emptyList()),
    val selectedPid: Long? = null,
    val selectedSummary: SelectedProcessVisualSummary? = null,
    val currentTotalRssMb: Long = 0,
    val activeProcessCount: Int = 0,
    val activeProjectCount: Int = 0,
    val isLoading: Boolean = true,
    val isEmpty: Boolean = true,
    val statusText: String? = null,
    val errorText: String? = null,
)
