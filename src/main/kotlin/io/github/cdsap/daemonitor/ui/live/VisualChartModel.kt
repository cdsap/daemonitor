package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

data class ProcessMemoryBars(
    val pid: Long,
    val label: String,
    val rssMb: Long,
    /** Live heap used when the Attach/JMX probe succeeds; null means unavailable. */
    val heapUsedMb: Long?,
    /** Configured `-Xmx` limit; null means unavailable (never treated as zero). */
    val heapLimitMb: Long?,
    val rssFraction: Float,
    val heapUsedFraction: Float?,
    val heapLimitFraction: Float?,
)

data class VisualChartData(
    val totalRssMb: Long,
    val bars: List<ProcessMemoryBars>,
)

enum class TimelineMetric {
    RSS,
    HEAP_USED,
    HEAP_LIMIT,
}

data class TimelineSeries(
    val id: String,
    val label: String,
    val pid: Long? = null,
    val isTotal: Boolean = false,
    val metric: TimelineMetric = TimelineMetric.RSS,
) {
    /** Dashed stroke reserved for configured heap limit (`-Xmx`), not live occupancy. */
    val dashed: Boolean get() = metric == TimelineMetric.HEAP_LIMIT
}

data class RssTimelinePoint(
    val atMs: Long,
    val valuesBySeriesId: Map<String, Long>,
)

data class RssTimelineChartData(
    val series: List<TimelineSeries>,
    val points: List<RssTimelinePoint>,
) {
    fun value(point: RssTimelinePoint, series: TimelineSeries): Long =
        point.valuesBySeriesId[series.id] ?: 0L
}

object VisualChartModel {
    const val TOTAL_SERIES_ID = "total"
    const val TOTAL_HEAP_USED_SERIES_ID = "total-heap-used"
    const val TOTAL_HEAP_LIMIT_SERIES_ID = "total-heap-limit"
    const val DEFAULT_TIMELINE_WINDOW_MS = 30_000L

    /** @deprecated Prefer [TOTAL_HEAP_LIMIT_SERIES_ID]; kept for older test aliases. */
    const val TOTAL_HEAP_SERIES_ID = TOTAL_HEAP_LIMIT_SERIES_ID

    fun fromProcesses(processes: List<GradleProcess>): VisualChartData {
        if (processes.isEmpty()) {
            return VisualChartData(totalRssMb = 0, bars = emptyList())
        }

        val totalRssMb = processes.sumOf { it.rssMemoryMb }
        val scaleMb = processes
            .flatMap { process ->
                listOfNotNull(
                    process.rssMemoryMb,
                    process.liveHeap?.takeIf { it.available }?.usedMb,
                    process.maxHeapMb,
                )
            }
            .maxOrNull()
            ?.takeIf { it > 0 }
            ?: 1L

        val bars = processes.map { process ->
            val used = process.liveHeap?.takeIf { it.available }?.usedMb
            ProcessMemoryBars(
                pid = process.pid,
                label = process.chartLabel(),
                rssMb = process.rssMemoryMb,
                heapUsedMb = used,
                heapLimitMb = process.maxHeapMb,
                rssFraction = fraction(process.rssMemoryMb, scaleMb),
                heapUsedFraction = used?.let { fraction(it, scaleMb) },
                heapLimitFraction = process.maxHeapMb?.let { fraction(it, scaleMb) },
            )
        }

        return VisualChartData(totalRssMb = totalRssMb, bars = bars)
    }

    fun timelineChart(
        samples: List<RssTimelineSample>,
        processes: List<GradleProcess>,
        windowEndMs: Long? = null,
        windowDurationMs: Long = DEFAULT_TIMELINE_WINDOW_MS,
    ): RssTimelineChartData {
        val endMs = windowEndMs ?: samples.lastOrNull()?.atMs
        val visible = if (endMs == null) {
            samples
        } else {
            val startMs = endMs - windowDurationMs
            samples.filter { it.atMs in startMs..endMs }
        }

        val liveByPid = processes.associateBy { it.pid }
        val orderedPids = processes
            .sortedByDescending { it.rssMemoryMb }
            .map { it.pid } +
            visible
                .flatMap { it.byPid.keys + it.heapLimitByPid.keys + it.heapUsedByPid.keys }
                .distinct()
                .filterNot { it in liveByPid }
                .sorted()

        val processSeries = orderedPids.flatMap { pid ->
            val baseLabel = liveByPid[pid]?.chartLabel() ?: "PID $pid"
            val hasHeapLimit = visible.any { pid in it.heapLimitByPid } || liveByPid[pid]?.maxHeapMb != null
            val hasHeapUsed = visible.any { pid in it.heapUsedByPid } ||
                liveByPid[pid]?.liveHeap?.available == true
            buildList {
                add(
                    TimelineSeries(
                        id = rssSeriesId(pid),
                        label = "$baseLabel · RSS",
                        pid = pid,
                        metric = TimelineMetric.RSS,
                    ),
                )
                if (hasHeapUsed) {
                    add(
                        TimelineSeries(
                            id = heapUsedSeriesId(pid),
                            label = "$baseLabel · Heap used",
                            pid = pid,
                            metric = TimelineMetric.HEAP_USED,
                        ),
                    )
                }
                if (hasHeapLimit) {
                    add(
                        TimelineSeries(
                            id = heapLimitSeriesId(pid),
                            label = "$baseLabel · Heap limit",
                            pid = pid,
                            metric = TimelineMetric.HEAP_LIMIT,
                        ),
                    )
                }
            }
        }

        val series = listOf(
            TimelineSeries(id = TOTAL_SERIES_ID, label = "Total RSS", isTotal = true, metric = TimelineMetric.RSS),
            TimelineSeries(
                id = TOTAL_HEAP_USED_SERIES_ID,
                label = "Total heap used",
                isTotal = true,
                metric = TimelineMetric.HEAP_USED,
            ),
            TimelineSeries(
                id = TOTAL_HEAP_LIMIT_SERIES_ID,
                label = "Total heap limit",
                isTotal = true,
                metric = TimelineMetric.HEAP_LIMIT,
            ),
        ) + processSeries

        val points = visible.map { sample ->
            val values = linkedMapOf<String, Long>()
            values[TOTAL_SERIES_ID] = sample.totalRssMb
            values[TOTAL_HEAP_USED_SERIES_ID] = sample.heapUsedByPid.values.sum()
            values[TOTAL_HEAP_LIMIT_SERIES_ID] = sample.heapLimitByPid.values.sum()
            processSeries.forEach { item ->
                val pid = item.pid ?: return@forEach
                val value = when (item.metric) {
                    TimelineMetric.RSS -> sample.byPid[pid]
                    TimelineMetric.HEAP_USED -> sample.heapUsedByPid[pid]
                        ?: liveByPid[pid]?.liveHeap?.takeIf { it.available }?.usedMb
                    TimelineMetric.HEAP_LIMIT -> sample.heapLimitByPid[pid] ?: liveByPid[pid]?.maxHeapMb
                } ?: 0L
                values[item.id] = value
            }
            RssTimelinePoint(atMs = sample.atMs, valuesBySeriesId = values)
        }

        return RssTimelineChartData(series = series, points = points)
    }

    fun rssSeriesId(pid: Long): String = "pid-$pid-rss"
    fun heapUsedSeriesId(pid: Long): String = "pid-$pid-heap-used"
    fun heapLimitSeriesId(pid: Long): String = "pid-$pid-heap-limit"

    /** @deprecated Prefer [heapLimitSeriesId]. */
    fun heapSeriesId(pid: Long): String = heapLimitSeriesId(pid)

    private fun GradleProcess.chartLabel(): String {
        val project = projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return listOfNotNull(type.displayLabel(), project, "PID $pid").joinToString(" · ")
    }

    private fun fraction(value: Long, scaleMb: Long): Float =
        (value.toFloat() / scaleMb.toFloat()).coerceIn(0f, 1f)
}

internal fun ProcessType.displayLabel(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon"
    ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
    ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
    ProcessType.TEST_WORKER -> "Test worker"
    ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
}
