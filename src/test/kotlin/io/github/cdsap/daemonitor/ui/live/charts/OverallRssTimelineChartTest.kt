package io.github.cdsap.daemonitor.ui.live.charts

import io.github.cdsap.daemonitor.ui.live.TimelineMetric
import io.github.cdsap.daemonitor.ui.live.TimelineSeries
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverallRssTimelineChartTest {

    @Test
    fun `dense charts show totals top processes and selected process by default`() {
        val series = listOf(
            TimelineSeries(id = "total", label = "Total RSS", isTotal = true),
            TimelineSeries(id = "total-heap", label = "Total Heap", isTotal = true, metric = TimelineMetric.HEAP),
        ) + (1L..6L).flatMap { pid ->
            listOf(
                TimelineSeries(id = "pid-$pid-rss", label = "PID $pid RSS", pid = pid),
                TimelineSeries(id = "pid-$pid-heap", label = "PID $pid Heap", pid = pid, metric = TimelineMetric.HEAP),
            )
        }

        val visible = defaultVisibleSeriesIds(series, selectedPid = 6L)

        assertTrue("total" in visible)
        assertTrue("total-heap" in visible)
        assertTrue("pid-1-rss" in visible)
        assertTrue("pid-4-heap" in visible)
        assertTrue("pid-6-rss" in visible)
        assertTrue("pid-6-heap" in visible)
        assertFalse("pid-5-rss" in visible)
        assertFalse("pid-5-heap" in visible)
    }
}
