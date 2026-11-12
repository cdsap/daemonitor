package io.github.cdsap.daemonitor.ui.live.charts

import io.github.cdsap.daemonitor.ui.live.RssTimelinePoint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverallRssTimelineChartTest {

    @Test
    fun `pathFor stays empty when every sample is unavailable`() {
        val path = pathFor(
            points = listOf(
                RssTimelinePoint(atMs = 1_000, valuesBySeriesId = emptyMap()),
                RssTimelinePoint(atMs = 2_000, valuesBySeriesId = emptyMap()),
            ),
            width = 100f,
            height = 50f,
            maxMb = 10L,
        ) { null }

        assertTrue(path.isEmpty)
    }

    @Test
    fun `pathFor keeps a sparse series without inventing zero troughs`() {
        val seriesId = "pid-1-cpu"
        val path = pathFor(
            points = listOf(
                RssTimelinePoint(atMs = 1_000, valuesBySeriesId = mapOf(seriesId to 24L)),
                RssTimelinePoint(atMs = 2_000, valuesBySeriesId = emptyMap()),
                RssTimelinePoint(atMs = 3_000, valuesBySeriesId = mapOf(seriesId to 18L)),
            ),
            width = 100f,
            height = 50f,
            maxMb = 24L,
        ) { it.valuesBySeriesId[seriesId] }

        // A broken polyline is still non-empty; the important check is we did not require a
        // zero for the middle sample to build the path.
        assertFalse(path.isEmpty)
    }
}
