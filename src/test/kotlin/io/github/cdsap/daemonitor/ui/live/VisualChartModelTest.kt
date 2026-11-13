package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisualChartModelTest {

    @Test
    fun `live type labels and detail titles include bazel server`() {
        assertEquals("Bazel server", ProcessType.BAZEL_SERVER.displayLabel())
        assertEquals("Bazel server / Process detail", ProcessType.BAZEL_SERVER.detailPanelTitle())
        assertEquals("Gradle daemon / Process detail", ProcessType.GRADLE_DAEMON.detailPanelTitle())
        assertEquals("Process detail", ProcessType.GRADLE_WORKER.detailPanelTitle())
    }

    @Test
    fun `bars expose rss live used and optional heap limit with shared scale`() {
        val data = VisualChartModel.fromProcesses(
            listOf(
                process(pid = 1, rss = 512, heapLimit = 4096, heapUsed = 256),
                process(pid = 2, rss = 8192, heapLimit = null, heapUsed = null),
            ),
        )

        assertEquals(8704L, data.totalRssMb)
        assertEquals(512L, data.bars[0].rssMb)
        assertEquals(256L, data.bars[0].heapUsedMb)
        assertEquals(4096L, data.bars[0].heapLimitMb)
        assertEquals(0.0625f, data.bars[0].rssFraction)
        assertEquals(0.5f, data.bars[0].heapLimitFraction)
        assertEquals(1.0f, data.bars[1].rssFraction)
        assertNull(data.bars[1].heapUsedMb)
        assertNull(data.bars[1].heapLimitMb)
        assertNull(data.bars[1].heapUsedFraction)
        assertNull(data.bars[1].heapLimitFraction)
    }

    @Test
    fun `missing heap values stay unavailable`() {
        val bar = VisualChartModel.fromProcesses(
            listOf(process(pid = 12, rss = 180, heapLimit = null, heapUsed = null)),
        ).bars.single()

        assertEquals(180L, bar.rssMb)
        assertNull(bar.heapUsedMb)
        assertNull(bar.heapLimitMb)
        assertNull(bar.heapUsedFraction)
        assertNull(bar.heapLimitFraction)
    }

    @Test
    fun `timeline chart includes rss used and limit series`() {
        val processes = listOf(
            process(pid = 1, type = ProcessType.GRADLE_DAEMON, rss = 60, heapLimit = 512, heapUsed = 80),
            process(pid = 2, type = ProcessType.GRADLE_WORKER, rss = 40, heapLimit = null, heapUsed = null),
        )
        val chart = VisualChartModel.timelineChart(
            samples = listOf(
                RssTimelineSample(
                    atMs = 1_000,
                    totalRssMb = 100,
                    byPid = mapOf(1L to 60L, 2L to 40L),
                    heapLimitByPid = mapOf(1L to 512L),
                    heapUsedByPid = mapOf(1L to 80L),
                ),
                RssTimelineSample(
                    atMs = 2_000,
                    totalRssMb = 150,
                    byPid = mapOf(1L to 90L, 2L to 60L),
                    heapLimitByPid = mapOf(1L to 512L),
                    heapUsedByPid = mapOf(1L to 90L),
                ),
            ),
            processes = processes,
        )

        assertEquals(
            listOf(
                VisualChartModel.TOTAL_SERIES_ID,
                VisualChartModel.TOTAL_HEAP_USED_SERIES_ID,
                VisualChartModel.TOTAL_HEAP_LIMIT_SERIES_ID,
                VisualChartModel.rssSeriesId(1),
                VisualChartModel.heapUsedSeriesId(1),
                VisualChartModel.heapLimitSeriesId(1),
                VisualChartModel.rssSeriesId(2),
            ),
            chart.series.map { it.id },
        )
        assertTrue(chart.series.first().isTotal)
        assertEquals(
            TimelineMetric.HEAP_USED,
            chart.series.first { it.id == VisualChartModel.heapUsedSeriesId(1) }.metric,
        )
        assertEquals(
            TimelineMetric.HEAP_LIMIT,
            chart.series.first { it.id == VisualChartModel.heapLimitSeriesId(1) }.metric,
        )
        assertEquals(100L, chart.points[0].valuesBySeriesId[VisualChartModel.TOTAL_SERIES_ID])
        assertEquals(80L, chart.points[0].valuesBySeriesId[VisualChartModel.TOTAL_HEAP_USED_SERIES_ID])
        assertEquals(512L, chart.points[0].valuesBySeriesId[VisualChartModel.TOTAL_HEAP_LIMIT_SERIES_ID])
        assertEquals(60L, chart.points[0].valuesBySeriesId[VisualChartModel.rssSeriesId(1)])
        assertEquals(80L, chart.points[0].valuesBySeriesId[VisualChartModel.heapUsedSeriesId(1)])
        assertEquals(512L, chart.points[0].valuesBySeriesId[VisualChartModel.heapLimitSeriesId(1)])
        assertEquals(40L, chart.points[0].valuesBySeriesId[VisualChartModel.rssSeriesId(2)])
        assertEquals(90L, chart.points[1].valuesBySeriesId[VisualChartModel.rssSeriesId(1)])
    }

    @Test
    fun `timeline chart exposes extended metrics without making them default noise`() {
        val chart = VisualChartModel.timelineChart(
            samples = listOf(
                RssTimelineSample(
                    atMs = 1_000,
                    totalRssMb = 100,
                    byPid = mapOf(1L to 100L),
                    extendedByPid = mapOf(1L to ProcessMetricSample(threadCount = 24, readBytes = 4096)),
                ),
            ),
            processes = listOf(process(pid = 1, rss = 100, heapLimit = null, heapUsed = null)),
        )

        val threads = chart.series.first { it.id == VisualChartModel.extendedSeriesId(1, TimelineMetric.THREADS) }
        assertEquals(false, threads.defaultVisible)
        assertEquals(24L, chart.points.single().valuesBySeriesId[threads.id])
        assertTrue(chart.series.any { it.metric == TimelineMetric.READ_BYTES })
    }

    @Test
    fun `forMode keeps memory totals and drops io series`() {
        val chart = VisualChartModel.timelineChart(
            samples = listOf(
                RssTimelineSample(
                    atMs = 1_000,
                    totalRssMb = 100,
                    byPid = mapOf(1L to 100L),
                    heapUsedByPid = mapOf(1L to 40L),
                    heapLimitByPid = mapOf(1L to 512L),
                    extendedByPid = mapOf(1L to ProcessMetricSample(threadCount = 24, readBytes = 4096)),
                ),
            ),
            processes = listOf(process(pid = 1, rss = 100, heapLimit = 512, heapUsed = 40)),
        )

        val memory = VisualChartModel.forMode(chart, VisualMetricMode.MEMORY)
        assertTrue(memory.series.any { it.id == VisualChartModel.TOTAL_SERIES_ID })
        assertTrue(memory.series.none { it.metric == TimelineMetric.READ_BYTES })

        val io = VisualChartModel.forMode(chart, VisualMetricMode.IO)
        assertTrue(io.series.none { it.isTotal })
        assertTrue(io.series.all { it.metric in setOf(TimelineMetric.READ_BYTES, TimelineMetric.WRITE_BYTES) })
    }

    @Test
    fun `timeline chart exposes cpu series and cpu mode without inventing zeros`() {
        val chart = VisualChartModel.timelineChart(
            samples = listOf(
                RssTimelineSample(
                    atMs = 1_000,
                    totalRssMb = 100,
                    byPid = mapOf(1L to 100L, 2L to 50L),
                    extendedByPid = mapOf(1L to ProcessMetricSample(cpuPercent = 24L)),
                ),
                RssTimelineSample(
                    atMs = 2_000,
                    totalRssMb = 110,
                    byPid = mapOf(1L to 110L, 2L to 50L),
                ),
            ),
            processes = listOf(
                process(pid = 1, rss = 100, heapLimit = null, heapUsed = null),
                process(pid = 2, rss = 50, heapLimit = null, heapUsed = null),
            ),
        )

        val cpuSeriesId = VisualChartModel.extendedSeriesId(1, TimelineMetric.CPU)
        val cpuSeries = chart.series.first { it.id == cpuSeriesId }
        assertEquals(TimelineMetric.CPU, cpuSeries.metric)
        assertEquals(24L, chart.points[0].valuesBySeriesId[cpuSeriesId])
        assertTrue(cpuSeriesId !in chart.points[1].valuesBySeriesId)
        assertTrue(chart.series.none { it.pid == 2L && it.metric == TimelineMetric.CPU })

        val cpuMode = VisualChartModel.forMode(chart, VisualMetricMode.CPU)
        assertTrue(cpuMode.series.none { it.isTotal })
        assertTrue(cpuMode.series.all { it.metric == TimelineMetric.CPU })
        assertEquals("%", VisualMetricMode.CPU.axisUnit)
    }

    @Test
    fun `timeline chart keeps only the active window`() {
        val chart = VisualChartModel.timelineChart(
            samples = listOf(
                RssTimelineSample(atMs = 0, totalRssMb = 10),
                RssTimelineSample(atMs = 20_000, totalRssMb = 20),
                RssTimelineSample(atMs = 40_000, totalRssMb = 30),
                RssTimelineSample(atMs = 55_000, totalRssMb = 40),
            ),
            processes = emptyList(),
            windowEndMs = 55_000,
            windowDurationMs = 30_000,
        )

        assertEquals(listOf(30L, 40L), chart.points.map { it.valuesBySeriesId.getValue(VisualChartModel.TOTAL_SERIES_ID) })
    }

    @Test
    fun `retained timeline does not fill missing heap values from live processes`() {
        val chart = VisualChartModel.timelineChart(
            samples = listOf(RssTimelineSample(atMs = 1_000, totalRssMb = 100, byPid = mapOf(1L to 100L))),
            processes = listOf(process(pid = 1, rss = 100, heapLimit = 512, heapUsed = 80)),
            useLiveValueFallbacks = false,
        )

        assertTrue(
            chart.series.none {
                it.pid == 1L && (it.metric == TimelineMetric.HEAP_USED || it.metric == TimelineMetric.HEAP_LIMIT)
            },
        )
    }

    @Test
    fun `empty process list returns empty chart data`() {
        val data = VisualChartModel.fromProcesses(emptyList())
        assertEquals(0L, data.totalRssMb)
        assertEquals(emptyList(), data.bars)
    }

    private fun process(
        pid: Long,
        type: ProcessType = ProcessType.GRADLE_WRAPPER,
        projectPath: String? = "/Users/dev/project",
        rss: Long,
        heapLimit: Long?,
        heapUsed: Long? = null,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = type,
        commandLine = "java org.gradle.wrapper.GradleWrapperMain build",
        workingDirectory = projectPath,
        projectPath = projectPath,
        cpuPercent = 10.0,
        rssMemoryMb = rss,
        maxHeapMb = heapLimit,
        minHeapMb = null,
        gc = "G1",
        startTimeMs = 1_700_000_000_000,
        status = "RUNNING",
        liveHeap = heapUsed?.let {
            LiveJvmHeap(
                usedMb = it,
                committedMb = it + 16,
                maxMb = heapLimit,
                sampledAtMs = 1_700_000_000_000,
                available = true,
            )
        },
    )
}
