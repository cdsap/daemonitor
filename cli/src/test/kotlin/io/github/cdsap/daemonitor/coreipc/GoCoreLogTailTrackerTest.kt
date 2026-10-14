package io.github.cdsap.daemonitor.coreipc

import kotlin.test.Test
import kotlin.test.assertEquals

class GoCoreLogTailTrackerTest {
    @Test
    fun `returns all lines when there is no previous tail`() {
        val tracker = GoCoreLogTailTracker()

        assertEquals(
            listOf("a", "b"),
            tracker.newLinesSince(pid = 42, current = listOf("a", "b")),
        )
    }

    @Test
    fun `returns appended lines`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 42, current = listOf("a", "b"))

        assertEquals(
            listOf("c"),
            tracker.newLinesSince(pid = 42, current = listOf("a", "b", "c")),
        )
    }

    @Test
    fun `returns lines after the overlap in a fixed-size ring tail`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 42, current = listOf("a", "b", "c"))

        assertEquals(
            listOf("d"),
            tracker.newLinesSince(pid = 42, current = listOf("b", "c", "d")),
        )
    }

    @Test
    fun `returns no lines for an empty current tail`() {
        val tracker = GoCoreLogTailTracker()
        tracker.newLinesSince(pid = 42, current = listOf("a", "b"))

        assertEquals(emptyList(), tracker.newLinesSince(pid = 42, current = emptyList()))
        assertEquals(listOf("c"), tracker.newLinesSince(pid = 42, current = listOf("c")))
    }
}
