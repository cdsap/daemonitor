package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.persistence.ProcessSample
import kotlin.math.ceil

data class ProcessMemoryBars(
    val pid: Long,
    val label: String,
    val rssMb: Long,
    val heapAllocationMb: Long?,
    val rssFraction: Float,
    val heapFraction: Float?,
)

data class VisualChartData(
    val totalRssMb: Long,
    val bars: List<ProcessMemoryBars>,
)

enum class TimelineMetric {
    RSS,
    HEAP,
}

data class TimelineSeries(
    val id: String,
    val label: String,
    val pid: Long? = null,
    val isTotal: Boolean = false,
    val metric: TimelineMetric = TimelineMetric.RSS,
) {
    val dashed: Boolean get() = metric == TimelineMetric.HEAP
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
    const val TOTAL_HEAP_SERIES_ID = "total-heap"
    const val DEFAULT_TIMELINE_WINDOW_MS = 30_000L
    const val ALL_RETAINED_TARGET_POINTS = 240

    fun fromProcesses(processes: List<GradleProcess>): VisualChartData {
        if (processes.isEmpty()) {
            return VisualChartData(totalRssMb = 0, bars = emptyList())
        }

        val totalRssMb = processes.sumOf { it.rssMemoryMb }
        val scaleMb = processes
            .flatMap { process -> listOfNotNull(process.rssMemoryMb, process.maxHeapMb) }
            .maxOrNull()
            ?.takeIf { it > 0 }
            ?: 1L

        val bars = processes.map { process ->
            ProcessMemoryBars(
                pid = process.pid,
                label = process.chartLabel(),
                rssMb = process.rssMemoryMb,
                heapAllocationMb = process.maxHeapMb,
                rssFraction = fraction(process.rssMemoryMb, scaleMb),
                heapFraction = process.maxHeapMb?.let { fraction(it, scaleMb) },
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
                .flatMap { it.byPid.keys + it.heapByPid.keys }
                .distinct()
                .filterNot { it in liveByPid }
                .sorted()

        val processSeries = orderedPids.flatMap { pid ->
            val baseLabel = liveByPid[pid]?.chartLabel() ?: "PID $pid"
            val hasHeap = visible.any { pid in it.heapByPid } || liveByPid[pid]?.maxHeapMb != null
            buildList {
                add(
                    TimelineSeries(
                        id = rssSeriesId(pid),
                        label = "$baseLabel · RSS",
                        pid = pid,
                        metric = TimelineMetric.RSS,
                    ),
                )
                if (hasHeap) {
                    add(
                        TimelineSeries(
                            id = heapSeriesId(pid),
                            label = "$baseLabel · Heap",
                            pid = pid,
                            metric = TimelineMetric.HEAP,
                        ),
                    )
                }
            }
        }

        val series = listOf(
            TimelineSeries(id = TOTAL_SERIES_ID, label = "Total RSS", isTotal = true, metric = TimelineMetric.RSS),
            TimelineSeries(id = TOTAL_HEAP_SERIES_ID, label = "Total Heap", isTotal = true, metric = TimelineMetric.HEAP),
        ) + processSeries

        val points = visible.map { sample ->
            val values = linkedMapOf<String, Long>()
            values[TOTAL_SERIES_ID] = sample.totalRssMb
            values[TOTAL_HEAP_SERIES_ID] = sample.heapByPid.values.sum()
            processSeries.forEach { item ->
                val pid = item.pid ?: return@forEach
                val value = when (item.metric) {
                    TimelineMetric.RSS -> sample.byPid[pid]
                    TimelineMetric.HEAP -> sample.heapByPid[pid] ?: liveByPid[pid]?.maxHeapMb
                } ?: 0L
                values[item.id] = value
            }
            RssTimelinePoint(atMs = sample.atMs, valuesBySeriesId = values)
        }

        return RssTimelineChartData(series = series, points = points)
    }

    fun timelineChart(
        samples: List<ProcessSample>,
        range: VisualRange,
        liveProcesses: List<GradleProcess>,
        nowMs: Long,
    ): RssTimelineChartData {
        if (samples.isEmpty()) {
            return RssTimelineChartData(series = emptyList(), points = emptyList())
        }

        val bucketMs = bucketDurationMs(samples, range, nowMs)
        val buckets = samples
            .groupBy { sample -> bucketStart(sample.timestampMs, bucketMs) }
            .toSortedMap()
            .map { (_, bucketSamples) -> bucketSamples.toTimelineSample() }

        val liveByPid = liveProcesses.associateBy { it.pid }
        val latestSampleByPid = samples.groupBy { it.pid }.mapValues { (_, pidSamples) ->
            pidSamples.maxByOrNull { it.timestampMs }
        }
        val pidsByPeak = samples
            .groupBy { it.pid }
            .entries
            .sortedByDescending { (_, pidSamples) -> pidSamples.maxOf { it.rssMemoryMb } }
            .map { it.key }

        val processSeries = pidsByPeak.flatMap { pid ->
            val label = liveByPid[pid]?.chartLabel()
                ?: latestSampleByPid[pid]?.chartLabel()
                ?: "PID $pid"
            val hasHeap = samples.any { it.pid == pid && it.maxHeapMb != null } || liveByPid[pid]?.maxHeapMb != null
            buildList {
                add(
                    TimelineSeries(
                        id = rssSeriesId(pid),
                        label = "$label · RSS",
                        pid = pid,
                        metric = TimelineMetric.RSS,
                    ),
                )
                if (hasHeap) {
                    add(
                        TimelineSeries(
                            id = heapSeriesId(pid),
                            label = "$label · Heap",
                            pid = pid,
                            metric = TimelineMetric.HEAP,
                        ),
                    )
                }
            }
        }

        val series = listOf(
            TimelineSeries(id = TOTAL_SERIES_ID, label = "Total RSS", isTotal = true, metric = TimelineMetric.RSS),
            TimelineSeries(id = TOTAL_HEAP_SERIES_ID, label = "Total Heap", isTotal = true, metric = TimelineMetric.HEAP),
        ) + processSeries

        val points = buckets.map { sample ->
            val values = linkedMapOf<String, Long>()
            values[TOTAL_SERIES_ID] = sample.totalRssMb
            values[TOTAL_HEAP_SERIES_ID] = sample.heapByPid.values.sum()
            processSeries.forEach { item ->
                val pid = item.pid ?: return@forEach
                val value = when (item.metric) {
                    TimelineMetric.RSS -> sample.byPid[pid]
                    TimelineMetric.HEAP -> sample.heapByPid[pid] ?: liveByPid[pid]?.maxHeapMb
                } ?: 0L
                values[item.id] = value
            }
            RssTimelinePoint(atMs = sample.atMs, valuesBySeriesId = values)
        }

        return RssTimelineChartData(series = series, points = points)
    }

    fun rssSeriesId(pid: Long): String = "pid-$pid-rss"
    fun heapSeriesId(pid: Long): String = "pid-$pid-heap"

    private fun GradleProcess.chartLabel(): String {
        val project = projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return listOfNotNull(type.displayLabel(), project, "PID $pid").joinToString(" · ")
    }

    private fun fraction(value: Long, scaleMb: Long): Float =
        (value.toFloat() / scaleMb.toFloat()).coerceIn(0f, 1f)

    private fun bucketDurationMs(samples: List<ProcessSample>, range: VisualRange, nowMs: Long): Long {
        range.bucketMs?.let { return it }
        if (range != VisualRange.ALL_RETAINED) return 1L
        val first = samples.firstOrNull()?.timestampMs ?: return 1L
        val last = maxOf(nowMs, samples.lastOrNull()?.timestampMs ?: first)
        val duration = (last - first).coerceAtLeast(1L)
        return ceil(duration.toDouble() / ALL_RETAINED_TARGET_POINTS.toDouble()).toLong().coerceAtLeast(1L)
    }

    private fun bucketStart(timestampMs: Long, bucketMs: Long): Long =
        (timestampMs / bucketMs) * bucketMs

    private fun List<ProcessSample>.toTimelineSample(): RssTimelineSample {
        val ordered = sortedBy { it.timestampMs }
        val rssByPid = ordered
            .groupBy { it.pid }
            .mapValues { (_, pidSamples) -> pidSamples.maxOf { it.rssMemoryMb } }
        val heapByPid = ordered
            .groupBy { it.pid }
            .mapNotNull { (pid, pidSamples) ->
                pidSamples.asReversed().firstNotNullOfOrNull { it.maxHeapMb }?.let { pid to it }
            }
            .toMap()
        return RssTimelineSample(
            atMs = ordered.last().timestampMs,
            totalRssMb = rssByPid.values.sum(),
            byPid = rssByPid,
            heapByPid = heapByPid,
        )
    }

    private fun ProcessSample.chartLabel(): String {
        val project = projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return listOfNotNull(processType.displayLabel(), project, "PID $pid").joinToString(" · ")
    }
}

internal fun ProcessType.displayLabel(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon"
    ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
    ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
    ProcessType.TEST_WORKER -> "Test worker"
    ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
}
