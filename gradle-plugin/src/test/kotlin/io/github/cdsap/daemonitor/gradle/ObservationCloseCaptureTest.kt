package io.github.cdsap.daemonitor.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for empty GBOS observations: after Gradle tears down shared services,
 * Provider.get() constructs a new BuildService that opens/closes an empty session B.
 * Publishing must close the captured session A (idempotent) instead.
 */
class ObservationCloseCaptureTest {
    @Test
    fun `late recreate publishes empty summary and overwrites the report`() {
        var report = ""
        val sessionA = lifecycle(summary = GOOD_SUMMARY) { report = it }
        sessionA.openOnce()
        sessionA.closeOnce() // AutoCloseable teardown of the real window
        assertTrue(report.contains("\"pid\":42"), report)

        val sessionB = lifecycle(summary = EMPTY_SUMMARY) { report = it }
        sessionB.openOnce()
        val published = sessionB.closeOnce() // buggy: service.get() inside buildFinished

        assertEquals(EMPTY_SUMMARY, published)
        assertTrue(report.contains("\"processes\":[]"), report)
        assertFalse(report.contains("\"pid\":42"), report)
    }

    @Test
    fun `captured close returns the real summary after teardown`() {
        var report = ""
        val sessionA = lifecycle(summary = GOOD_SUMMARY) { report = it }
        sessionA.openOnce()
        val publish = ObservationClose.callback(sessionA)

        sessionA.closeOnce() // AutoCloseable runs first
        assertEquals(GOOD_SUMMARY, report)

        val published = publish() // Develocity buildFinished uses the capture
        assertEquals(GOOD_SUMMARY, published)
        assertEquals(GOOD_SUMMARY, report)
        assertTrue(published!!.contains("\"pid\":42"))
    }

    private fun lifecycle(
        summary: String,
        onSummary: (String) -> Unit,
    ) = ObservationSessionLifecycle(
        enabled = true,
        open = { HttpResult(201, "{}") },
        close = { HttpResult(200, summary) },
        onSummary = onSummary,
        warn = { _, _ -> },
    )

    private companion object {
        const val GOOD_SUMMARY =
            """{"name":"A","opened_at_ms":1,"closed_at_ms":5000,"processes":[{"pid":42}],"builds":{"count":0}}"""
        const val EMPTY_SUMMARY =
            """{"name":"B","opened_at_ms":9,"closed_at_ms":11,"processes":[],"builds":{"count":0}}"""
    }
}
