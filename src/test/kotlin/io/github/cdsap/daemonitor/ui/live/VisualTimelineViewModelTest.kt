package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.persistence.RetainedProcessSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualTimelineViewModelTest {
    @Test
    fun `range switch groups retained process rows into timeline snapshots`() {
        val viewModel = VisualTimelineViewModel()

        val request = viewModel.selectRange(VisualTimeRange.FIFTEEN_MINUTES)
        assertTrue(viewModel.state.value.isLoading)
        viewModel.onSamples(
            request = request!!,
            range = VisualTimeRange.FIFTEEN_MINUTES,
            samples = listOf(
                RetainedProcessSample(timestampMs = 1_000, pid = 11, rssMemoryMb = 100, heapUsedMb = 40, heapAvailable = true),
                RetainedProcessSample(timestampMs = 1_000, pid = 12, rssMemoryMb = 200),
                RetainedProcessSample(timestampMs = 2_000, pid = 11, rssMemoryMb = 150, threadCount = 24),
            ),
        )

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(VisualTimeRange.FIFTEEN_MINUTES, state.range)
        assertEquals(listOf(300L, 150L), state.samples.map { it.totalRssMb })
        assertEquals(mapOf(11L to 100L, 12L to 200L), state.samples.first().byPid)
        assertEquals(mapOf(11L to 40L), state.samples.first().heapUsedByPid)
        assertEquals(24L, state.samples.last().extendedByPid.getValue(11).threadCount)
    }

    @Test
    fun `empty retained range becomes an explicit loaded empty state`() {
        val viewModel = VisualTimelineViewModel()
        val request = viewModel.selectRange(VisualTimeRange.ONE_HOUR)!!

        viewModel.onSamples(request, VisualTimeRange.ONE_HOUR, emptyList())

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.samples.isEmpty())
    }

    @Test
    fun `returning to live ignores an older retained range response`() {
        val viewModel = VisualTimelineViewModel()
        val request = viewModel.selectRange(VisualTimeRange.ONE_HOUR)!!

        viewModel.selectRange(VisualTimeRange.LIVE)
        viewModel.onSamples(
            request,
            VisualTimeRange.ONE_HOUR,
            listOf(RetainedProcessSample(timestampMs = 1_000, pid = 11, rssMemoryMb = 100)),
        )

        assertEquals(VisualTimeRange.LIVE, viewModel.state.value.range)
        assertTrue(viewModel.state.value.samples.isEmpty())
    }
}
