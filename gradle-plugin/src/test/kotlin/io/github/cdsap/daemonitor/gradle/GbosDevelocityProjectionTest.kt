package io.github.cdsap.daemonitor.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbosDevelocityProjectionTest {
    @Test
    fun `phase one RSS summary becomes schema and one producer fragment per process`() {
        val summary = ObservationSummary(
            name = "session-1",
            association = "confirmed",
            hostId = "host-a",
            processes = listOf(
                ProcessRssSummary("host-a", 42, 100, 3, 10, 30, 20, 10, "confirmed"),
                ProcessRssSummary("host-a", 43, 100, 2, 8, 12, 9, 1, "inferred"),
            ),
            buildsCount = 1,
            buildsSuccessCount = 1,
            buildsFailureCount = 0,
        )

        val values = GbosDevelocityProjection.values(summary)
        assertEquals("1.0.0", values.first { it.first == "gbos.schema" }.second)
        assertEquals("daemonitor", values.first { it.first == "${GbosDevelocityProjection.NAMESPACE}.name" }.second)
        val fragments = values.filter { it.first == GbosDevelocityProjection.OBSERVATION_KEY }.map { it.second }
        assertEquals(2, fragments.size)
        assertTrue(fragments[0].contains("\"process.pid\":42"))
        assertTrue(fragments[0].contains("\"jvm.process.memory.rss\""))
        assertTrue(fragments[1].contains("\"process.pid\":43"))
    }

    @Test
    fun `cored close response parser retains phase one RSS values`() {
        val json = """{"name":"s","association":"confirmed","host_id":"h","processes":[{"host_id":"h","pid":7,"start_time_ms":11,"sample_count":3,"first_rss_mb":10,"peak_rss_mb":30,"last_rss_mb":20,"delta_rss_mb":10,"association":"confirmed"}],"builds":{"count":1,"success_count":1,"failure_count":0}}"""
        val process = ObservationSummaryParser.parse(json).processes.single()
        assertEquals(7, process.pid)
        assertEquals(30, process.peakRssMb)
        assertEquals(10, process.deltaRssMb)
    }
}
