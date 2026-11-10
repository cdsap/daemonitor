package io.github.cdsap.daemonitor.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservationSessionLifecycleTest {
    @Test
    fun `one successful close feeds the report sink exactly once`() {
        val warnings = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val lifecycle = ObservationSessionLifecycle(
            enabled = true,
            open = { HttpResult(201, "{}") },
            close = { HttpResult(200, "summary") },
            onSummary = summaries::add,
            warn = { message, _ -> warnings += message },
        )

        lifecycle.openOnce()
        assertEquals("summary", lifecycle.closeOnce())
        assertEquals("summary", lifecycle.closeOnce())
        assertEquals(listOf("summary"), summaries)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `cored down prevents close and all success sinks`() {
        val warnings = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val lifecycle = ObservationSessionLifecycle(
            enabled = true,
            open = { HttpResult(503, "down") },
            close = { error("close must not run") },
            onSummary = summaries::add,
            warn = { message, _ -> warnings += message },
        )

        lifecycle.openOnce()

        assertNull(lifecycle.closeOnce())
        assertEquals(emptyList(), summaries)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `close failure prevents report and GBOS success claims`() {
        val warnings = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val lifecycle = ObservationSessionLifecycle(
            enabled = true,
            open = { HttpResult(201, "{}") },
            close = { HttpResult(500, "failed") },
            onSummary = summaries::add,
            warn = { message, _ -> warnings += message },
        )

        lifecycle.openOnce()

        assertNull(lifecycle.closeOnce())
        assertEquals(emptyList(), summaries)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `disabled observation skips cored and sinks`() {
        var opens = 0
        val summaries = mutableListOf<String>()
        val lifecycle = ObservationSessionLifecycle(
            enabled = false,
            open = { opens++; HttpResult(201, "{}") },
            close = { error("close must not run") },
            onSummary = summaries::add,
            warn = { _, _ -> error("disabled observation must not warn") },
        )

        lifecycle.openOnce()

        assertNull(lifecycle.closeOnce())
        assertEquals(0, opens)
        assertEquals(emptyList(), summaries)
    }
}
