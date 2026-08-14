package io.github.cdsap.daemonitor.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionNavigationTest {

    @Test
    fun `down from no selection picks the first item`() {
        assertEquals(0, cycleIndex(3, null, 1))
    }

    @Test
    fun `up from no selection picks the last item`() {
        assertEquals(2, cycleIndex(3, null, -1))
    }

    @Test
    fun `down and up move within bounds`() {
        assertEquals(1, cycleIndex(3, 0, 1))
        assertEquals(0, cycleIndex(3, 1, -1))
    }

    @Test
    fun `selection wraps at both ends`() {
        assertEquals(0, cycleIndex(3, 2, 1))
        assertEquals(2, cycleIndex(3, 0, -1))
    }

    @Test
    fun `empty list or zero delta yields null`() {
        assertNull(cycleIndex(0, null, 1))
        assertNull(cycleIndex(3, 1, 0))
    }

    @Test
    fun `out of range current index behaves like no selection`() {
        assertEquals(0, cycleIndex(3, 99, 1))
        assertEquals(2, cycleIndex(3, -5, -1))
    }
}
