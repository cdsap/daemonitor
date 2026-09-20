package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveMetricLabelsTest {

    @Test
    fun `cpu distinguishes sampling from zero and real values`() {
        assertEquals(LiveMetricLabels.CPU_SAMPLING_COMPACT, LiveMetricLabels.cpuCompact(null))
        assertEquals(LiveMetricLabels.CPU_SAMPLING_DETAIL, LiveMetricLabels.cpuDetail(null))
        assertEquals("0%", LiveMetricLabels.cpuCompact(0.0))
        assertEquals("0%", LiveMetricLabels.cpuDetail(0.0))
        assertEquals("12%", LiveMetricLabels.cpuCompact(12.4))
        assertEquals("  0%", LiveMetricLabels.cpuCli(0.0))
        assertEquals("  …", LiveMetricLabels.cpuCli(null))
        assertNotEquals(LiveMetricLabels.cpuCompact(null), LiveMetricLabels.cpuCompact(0.0))
    }

    @Test
    fun `heap compact label is distinct from cpu sampling and never zero`() {
        assertEquals("n/a", LiveMetricLabels.liveHeapUsedCompact(null))
        assertEquals(
            "n/a",
            LiveMetricLabels.liveHeapUsedCompact(LiveJvmHeap.unavailable(1_000L)),
        )
        assertEquals(
            "128 MB",
            LiveMetricLabels.liveHeapUsedCompact(
                LiveJvmHeap(usedMb = 128, committedMb = 256, maxMb = 512, sampledAtMs = 1L, available = true),
            ),
        )
        assertNotEquals(
            LiveMetricLabels.CPU_SAMPLING_COMPACT,
            LiveMetricLabels.HEAP_UNAVAILABLE_COMPACT,
        )
    }

    @Test
    fun `heap detail explains when process type is not probed`() {
        assertEquals(
            "unavailable",
            LiveMetricLabels.liveHeapUsedDetail(LiveJvmHeap.unavailable(1L), ProcessType.GRADLE_DAEMON),
        )
        assertTrue(
            LiveMetricLabels.liveHeapUsedDetail(null, ProcessType.GRADLE_WRAPPER)
                .contains("wrappers"),
        )
        assertTrue(
            LiveMetricLabels.liveHeapUsedDetail(null, ProcessType.TEST_WORKER)
                .contains("test workers"),
        )
        assertTrue(LiveMetricLabels.isLiveHeapProbedType(ProcessType.GRADLE_DAEMON))
        assertTrue(LiveMetricLabels.isLiveHeapProbedType(ProcessType.KOTLIN_DAEMON))
        assertFalse(LiveMetricLabels.isLiveHeapProbedType(ProcessType.GRADLE_WRAPPER))
        assertFalse(LiveMetricLabels.isLiveHeapProbedType(ProcessType.TEST_WORKER))
    }
}
