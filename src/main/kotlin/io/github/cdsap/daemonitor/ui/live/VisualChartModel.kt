package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.displayLabel
import io.github.cdsap.daemonitor.domain.displayProjectLabel

enum class TimelineMetric {
    RSS,
    HEAP_USED,
    HEAP_LIMIT,
    THREADS,
    READ_BYTES,
    WRITE_BYTES,
    PAGE_FAULTS,
    CONTEXT_SWITCHES,
    OPEN_FILES,
    METASPACE,
    YOUNG_GC,
    OLD_GC,
    CPU,
}

/** First-class Visual modes — curated series per family, not legend archaeology. */
enum class VisualMetricMode(
    val label: String,
    val chartTitle: String,
    val hint: String,
    val axisUnit: String,
    val metrics: Set<TimelineMetric>,
    val includeTotals: Boolean,
) {
    MEMORY(
        label = "Memory",
        chartTitle = "Memory",
        hint = "Solid = RSS / heap used / metaspace, dashed = heap limit (-Xmx).",
        axisUnit = "MB",
        metrics = setOf(
            TimelineMetric.RSS,
            TimelineMetric.HEAP_USED,
            TimelineMetric.HEAP_LIMIT,
            TimelineMetric.METASPACE,
        ),
        includeTotals = true,
    ),
    CPU(
        label = "CPU",
        chartTitle = "CPU",
        hint = "Per-process CPU percent from the live window. Unavailable samples stay off the chart.",
        axisUnit = "%",
        metrics = setOf(TimelineMetric.CPU),
        includeTotals = false,
    ),
    IO(
        label = "I/O",
        chartTitle = "I/O",
        hint = "Per-process read and write byte totals from the live window.",
        axisUnit = "B",
        metrics = setOf(TimelineMetric.READ_BYTES, TimelineMetric.WRITE_BYTES),
        includeTotals = false,
    ),
    GC(
        label = "GC",
        chartTitle = "GC",
        hint = "Young and old collection counts over the live window.",
        axisUnit = "n",
        metrics = setOf(TimelineMetric.YOUNG_GC, TimelineMetric.OLD_GC),
        includeTotals = false,
    ),
    THREADS(
        label = "Threads",
        chartTitle = "Threads",
        hint = "Thread count and open file descriptors over the live window.",
        axisUnit = "n",
        metrics = setOf(TimelineMetric.THREADS, TimelineMetric.OPEN_FILES),
        includeTotals = false,
    ),
}

data class TimelineSeries(
    val id: String,
    val label: String,
    val pid: Long? = null,
    val isTotal: Boolean = false,
    val metric: TimelineMetric = TimelineMetric.RSS,
    val defaultVisible: Boolean = true,
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

    fun timelineChart(
        samples: List<RssTimelineSample>,
        processes: List<GradleProcess>,
        windowEndMs: Long? = null,
        windowDurationMs: Long = DEFAULT_TIMELINE_WINDOW_MS,
        useLiveValueFallbacks: Boolean = true,
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
                .flatMap { it.byPid.keys + it.heapLimitByPid.keys + it.heapUsedByPid.keys + it.extendedByPid.keys }
                .distinct()
                .filterNot { it in liveByPid }
                .sorted()

        val processSeries = orderedPids.flatMap { pid ->
            val baseLabel = liveByPid[pid]?.chartLabel() ?: "PID $pid"
            val hasHeapLimit = visible.any { pid in it.heapLimitByPid } ||
                (useLiveValueFallbacks && liveByPid[pid]?.maxHeapMb != null)
            val hasHeapUsed = visible.any { pid in it.heapUsedByPid } ||
                (useLiveValueFallbacks && liveByPid[pid]?.liveHeap?.available == true)
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
                extendedMetrics.forEach { (metric, label, value) ->
                    if (visible.any { value(it.extendedByPid[pid]) != null }) {
                        add(TimelineSeries(
                            id = extendedSeriesId(pid, metric),
                            label = "$baseLabel · $label",
                            pid = pid,
                            metric = metric,
                            defaultVisible = false,
                        ))
                    }
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
                val raw = when (item.metric) {
                    TimelineMetric.RSS -> sample.byPid[pid]
                    TimelineMetric.HEAP_USED -> sample.heapUsedByPid[pid]
                        ?: liveByPid[pid]?.liveHeap?.takeIf { useLiveValueFallbacks && it.available }?.usedMb
                    TimelineMetric.HEAP_LIMIT -> sample.heapLimitByPid[pid]
                        ?: liveByPid[pid]?.maxHeapMb?.takeIf { useLiveValueFallbacks }
                    else -> extendedValue(item.metric, sample.extendedByPid[pid])
                }
                // CPU (and other optional extended metrics) omit missing samples instead of
                // inventing zeros; RSS always has a value for live PIDs in the poll window.
                if (raw != null) {
                    values[item.id] = raw
                } else if (item.metric != TimelineMetric.CPU) {
                    values[item.id] = 0L
                }
            }
            RssTimelinePoint(atMs = sample.atMs, valuesBySeriesId = values)
        }

        return RssTimelineChartData(series = series, points = points)
    }

    private val DEFAULT_HIDDEN_METRICS = setOf(TimelineMetric.METASPACE, TimelineMetric.OPEN_FILES)

    /** Keep only series that belong to [mode]; totals only when the mode allows them. */
    fun forMode(chart: RssTimelineChartData, mode: VisualMetricMode): RssTimelineChartData {
        val filtered = chart.series.filter { series ->
            series.metric in mode.metrics && (mode.includeTotals || !series.isTotal)
        }.map { series ->
            series.copy(
                defaultVisible = series.metric !in DEFAULT_HIDDEN_METRICS,
            )
        }
        return chart.copy(series = filtered)
    }

    fun rssSeriesId(pid: Long): String = "pid-$pid-rss"
    fun heapUsedSeriesId(pid: Long): String = "pid-$pid-heap-used"
    fun heapLimitSeriesId(pid: Long): String = "pid-$pid-heap-limit"

    fun extendedSeriesId(pid: Long, metric: TimelineMetric): String = "pid-$pid-${metric.name.lowercase()}"

    private val extendedMetrics = listOf(
        Triple(TimelineMetric.THREADS, "Threads", { p: ProcessMetricSample? -> p?.threadCount }),
        Triple(TimelineMetric.READ_BYTES, "Read", { p: ProcessMetricSample? -> p?.readBytes }),
        Triple(TimelineMetric.WRITE_BYTES, "Write", { p: ProcessMetricSample? -> p?.writeBytes }),
        Triple(TimelineMetric.PAGE_FAULTS, "Page faults", { p: ProcessMetricSample? -> p?.pageFaults }),
        Triple(TimelineMetric.CONTEXT_SWITCHES, "Context switches", { p: ProcessMetricSample? -> p?.contextSwitches }),
        Triple(TimelineMetric.OPEN_FILES, "Open files", { p: ProcessMetricSample? -> p?.openFileDescriptors }),
        Triple(TimelineMetric.METASPACE, "Metaspace", { p: ProcessMetricSample? -> p?.metaspaceUsedMb }),
        Triple(TimelineMetric.YOUNG_GC, "Young GC", { p: ProcessMetricSample? -> p?.youngGcCount }),
        Triple(TimelineMetric.OLD_GC, "Old GC", { p: ProcessMetricSample? -> p?.oldGcCount }),
        Triple(TimelineMetric.CPU, "CPU", { p: ProcessMetricSample? -> p?.cpuPercent }),
    )

    private fun extendedValue(metric: TimelineMetric, sample: ProcessMetricSample?): Long? = when (metric) {
        TimelineMetric.THREADS -> sample?.threadCount
        TimelineMetric.READ_BYTES -> sample?.readBytes
        TimelineMetric.WRITE_BYTES -> sample?.writeBytes
        TimelineMetric.PAGE_FAULTS -> sample?.pageFaults
        TimelineMetric.CONTEXT_SWITCHES -> sample?.contextSwitches
        TimelineMetric.OPEN_FILES -> sample?.openFileDescriptors
        TimelineMetric.METASPACE -> sample?.metaspaceUsedMb
        TimelineMetric.YOUNG_GC -> sample?.youngGcCount
        TimelineMetric.OLD_GC -> sample?.oldGcCount
        TimelineMetric.CPU -> sample?.cpuPercent
        else -> null
    }

    private fun GradleProcess.chartLabel(): String {
        val project = displayProjectLabel(this)
        return listOfNotNull(type.displayLabel(), project, "PID $pid").joinToString(" · ")
    }
}

/** Section-card title for Live process detail (mirrors cored TUI detailTitle). */
internal fun ProcessType.detailPanelTitle(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon / Process detail"
    ProcessType.BAZEL_SERVER -> "Bazel server / Process detail"
    else -> "Process detail"
}
