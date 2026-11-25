package io.github.cdsap.daemonitor.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BazelBuildTelemetryParserTest {
    @Test
    fun `parses profile details and window summaries`() {
        val profile = assertNotNull(
            BazelBuildTelemetryParser.parseProfile(
                """{"bazel_version":"8.1.1","date":"2026-11-24","phase_seconds":{"analysis":1.2,"execution":4.5},"critical_path_seconds":3.1,"critical_path_components":5}""",
            ),
        )
        assertEquals("8.1.1", profile.bazelVersion)
        assertEquals(1.2, profile.phaseSeconds["analysis"])
        assertEquals(3.1, profile.criticalPathSeconds)
        assertEquals(5, profile.criticalPathComponents)

        val details = assertNotNull(
            BazelBuildTelemetryParser.parseDetails(
                bazelVersion = "8.1.1",
                sourceFormat = "json",
                collectionState = "sealed",
                metricsJson = """{"timing":{"analysis_phase_ms":1200,"execution_phase_ms":4500},"actions":{"created":100,"executed":42,"remote_cache_hits":30,"local_cache_hits":10,"local_cache_misses":2},"memory":{"used_heap_post_build_bytes":335544320,"peak_post_gc_heap_bytes":419430400},"workers":[{"process_id":1},{"process_id":2}],"test_totals":{"total":5,"passed":4,"failed":1,"cached":2}}""",
            ),
        )
        assertEquals("8.1.1", details.bazelVersion)
        assertEquals(1200L, details.analysisPhaseMs)
        assertEquals(42L, details.actionsExecuted)
        assertEquals(30L, details.remoteCacheHits)
        assertEquals(335544320L, details.usedHeapPostBuildBytes)
        assertEquals(2, details.workerCount)
        assertEquals(5, details.testsTotal)
        assertEquals(4, details.testsPassed)

        val windows = BazelBuildTelemetryParser.parseWindowSummaries(
            """[{"pid":4242,"process_type":"BAZEL_SERVER","sample_count":4,"first_rss_mb":500,"peak_rss_mb":900,"last_rss_mb":660,"delta_rss_mb":160,"first_heap_used_mb":200,"last_heap_used_mb":320,"delta_heap_used_mb":120,"delta_total_gc_count":3,"delta_young_gc_time_ms":45}]""",
        )
        assertEquals(1, windows.size)
        assertEquals(4242L, windows.single().pid)
        assertEquals(160L, windows.single().deltaRssMb)
        assertEquals(120L, windows.single().deltaHeapUsedMb)
        assertEquals(3L, windows.single().deltaTotalGcCount)
        assertEquals(45L, windows.single().deltaYoungGcTimeMs)
    }

    @Test
    fun `handles empty or malformed json gracefully`() {
        assertNull(BazelBuildTelemetryParser.parseProfile(null))
        assertNull(BazelBuildTelemetryParser.parseProfile("{}"))
        assertNull(BazelBuildTelemetryParser.parseDetails(null, null, null, "{}"))
        assertTrue(BazelBuildTelemetryParser.parseWindowSummaries(null).isEmpty())
        assertTrue(BazelBuildTelemetryParser.parseWindowSummaries("not-json").isEmpty())
    }
}
