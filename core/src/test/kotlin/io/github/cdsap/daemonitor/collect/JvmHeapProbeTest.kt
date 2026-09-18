package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmHeapProbeTest {

    @Test
    fun `unavailable probe never fabricates zero heap values`() {
        val probe = JvmHeapProbe { _, sampledAtMs -> LiveJvmHeap.unavailable(sampledAtMs) }
        val result = probe.probe(pid = 42, sampledAtMs = 1_000L)
        assertFalse(result.available)
        assertNull(result.usedMb)
        assertNull(result.committedMb)
        assertNull(result.maxMb)
        assertEquals(1_000L, result.sampledAtMs)
    }

    @Test
    fun `available probe preserves used committed and max`() {
        val probe = JvmHeapProbe { _, sampledAtMs ->
            LiveJvmHeap(
                usedMb = 120,
                committedMb = 256,
                maxMb = 512,
                sampledAtMs = sampledAtMs,
                available = true,
            )
        }
        val result = probe.probe(pid = 7, sampledAtMs = 2_000L)
        assertTrue(result.available)
        assertEquals(120L, result.usedMb)
        assertEquals(256L, result.committedMb)
        assertEquals(512L, result.maxMb)
        assertEquals(2_000L, result.sampledAtMs)
    }

    @Test
    fun `attach probe marks invalid pid unavailable`() {
        val result = AttachJvmHeapProbe(timeoutMs = 500L).probe(pid = -1, sampledAtMs = 3_000L)
        assertFalse(result.available)
        assertNull(result.usedMb)
        assertEquals(3_000L, result.sampledAtMs)
    }

    @Test
    fun `attach probe marks self pid unavailable to avoid deadlock`() {
        val result = AttachJvmHeapProbe(timeoutMs = 500L)
            .probe(pid = ProcessHandle.current().pid(), sampledAtMs = 4_000L)
        assertFalse(result.available)
        assertNull(result.usedMb)
        assertNull(result.committedMb)
        assertNull(result.maxMb)
        assertEquals(4_000L, result.sampledAtMs)
    }

    @Test
    fun `attach probe marks nonexistent pid unavailable within timeout`() {
        val result = AttachJvmHeapProbe(timeoutMs = 500L)
            .probe(pid = 1_000_000_007L, sampledAtMs = 5_000L)
        assertFalse(result.available)
        assertNull(result.usedMb)
        assertNull(result.committedMb)
        assertNull(result.maxMb)
        assertEquals(5_000L, result.sampledAtMs)
    }
}
