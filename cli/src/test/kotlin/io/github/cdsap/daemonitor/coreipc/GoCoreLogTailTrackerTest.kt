package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLogLine
import kotlin.test.Test
import kotlin.test.assertEquals

class GoCoreLogTailTrackerTest {
    @Test
    fun `returns all lines when there is no previous tail`() {
        val tracker = GoCoreLogTailTracker()

        assertEquals(
            listOf(DaemonLogLine("a"), DaemonLogLine("b")),
            tracker.newLinesSince(pid = 42, current = listOf("a", "b")),
        )
    }

    @Test
    fun `returns appended lines`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 42, current = listOf("a", "b"))

        assertEquals(
            listOf(DaemonLogLine("c")),
            tracker.newLinesSince(pid = 42, current = listOf("a", "b", "c")),
        )
    }

    @Test
    fun `returns lines after the overlap in a fixed-size ring tail`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 42, current = listOf("a", "b", "c"))

        assertEquals(
            listOf(DaemonLogLine("d")),
            tracker.newLinesSince(pid = 42, current = listOf("b", "c", "d")),
        )
    }

    @Test
    fun `returns no lines for an empty current tail`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 42, current = listOf("a", "b"))

        assertEquals(emptyList(), tracker.newLinesSince(pid = 42, current = emptyList()))
        assertEquals(listOf(DaemonLogLine("c")), tracker.newLinesSince(pid = 42, current = listOf("c")))
    }

    @Test
    fun `retainPids evicts disappeared pids while keeping active ones`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 1, current = listOf("a", "b"))
        tracker.newLinesSince(pid = 2, current = listOf("x", "y"))

        tracker.retainPids(setOf(2L))

        // Active PID 2 still diffs against its retained tail.
        assertEquals(emptyList(), tracker.newLinesSince(pid = 2, current = listOf("x", "y")))
        // Evicted PID 1 is treated as unseen if reused later.
        assertEquals(
            listOf(DaemonLogLine("a"), DaemonLogLine("b")),
            tracker.newLinesSince(pid = 1, current = listOf("a", "b")),
        )
    }
}
