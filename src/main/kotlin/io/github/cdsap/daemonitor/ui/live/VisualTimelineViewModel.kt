package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.persistence.RetainedProcessSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VisualTimeRange(val label: String, val durationMs: Long?) {
    LIVE("Live", null),
    FIFTEEN_MINUTES("15m", 15 * 60 * 1_000L),
    ONE_HOUR("1h", 60 * 60 * 1_000L),
}

data class VisualTimelineState(
    val range: VisualTimeRange = VisualTimeRange.LIVE,
    val samples: List<RssTimelineSample> = emptyList(),
    val isLoading: Boolean = false,
)

/** Owns retained-range loading state independently from the continuously updated Live buffer. */
class VisualTimelineViewModel {
    private var requestVersion = 0L
    private val _state = MutableStateFlow(VisualTimelineState())
    val state: StateFlow<VisualTimelineState> = _state.asStateFlow()

    /** Returns a request token when retained samples must be loaded; Live needs no database read. */
    fun selectRange(range: VisualTimeRange): Long? {
        requestVersion++
        _state.value = VisualTimelineState(
            range = range,
            isLoading = range != VisualTimeRange.LIVE,
        )
        return requestVersion.takeIf { range != VisualTimeRange.LIVE }
    }

    fun onSamples(request: Long, range: VisualTimeRange, samples: List<RetainedProcessSample>) {
        if (request != requestVersion || _state.value.range != range) return
        _state.value = VisualTimelineState(
            range = range,
            samples = samples.toTimelineSamples(),
            isLoading = false,
        )
    }

    private fun List<RetainedProcessSample>.toTimelineSamples(): List<RssTimelineSample> =
        groupBy(RetainedProcessSample::timestampMs)
            .toSortedMap()
            .map { (timestamp, rows) ->
                RssTimelineSample(
                    atMs = timestamp,
                    totalRssMb = rows.sumOf(RetainedProcessSample::rssMemoryMb),
                    byPid = rows.associate { it.pid to it.rssMemoryMb },
                    heapLimitByPid = rows.mapNotNull { row ->
                        row.maxHeapMb?.let { row.pid to it }
                    }.toMap(),
                    heapUsedByPid = rows.mapNotNull { row ->
                        row.heapUsedMb.takeIf { row.heapAvailable }?.let { row.pid to it }
                    }.toMap(),
                    extendedByPid = rows.mapNotNull { row ->
                        val metrics = ProcessMetricSample(
                            threadCount = row.threadCount,
                            readBytes = row.readBytes,
                            writeBytes = row.writeBytes,
                            pageFaults = sumIfPresent(row.minorPageFaults, row.majorPageFaults),
                            contextSwitches = sumIfPresent(
                                row.voluntaryContextSwitches,
                                row.involuntaryContextSwitches,
                            ),
                            openFileDescriptors = row.openFileDescriptors,
                            metaspaceUsedMb = row.metaspaceUsedMb,
                            youngGcCount = row.youngGcCount,
                            oldGcCount = row.oldGcCount,
                        )
                        (row.pid to metrics).takeUnless { metrics == ProcessMetricSample() }
                    }.toMap(),
                )
            }

    private fun sumIfPresent(first: Long?, second: Long?): Long? =
        if (first == null && second == null) null else listOfNotNull(first, second).sum()
}
