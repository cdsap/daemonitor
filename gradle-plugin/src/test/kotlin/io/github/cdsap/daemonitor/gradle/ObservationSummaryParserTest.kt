package io.github.cdsap.daemonitor.gradle

import io.github.cdsap.daemonitor.json.Json
import io.github.cdsap.daemonitor.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class ObservationSummaryParserTest {
    @Test
    fun `reads reordered fields nested objects escapes and exact process counters`() {
        val summary = ObservationSummaryParser.parse("""{
            "builds":{"failure_count":1,"success_count":2,"count":3},
            "processes":[{"nested":{"pid":99},"pid":9007199254740993,
                "host_id":"process-host","process_type":"worker } [ \u263a",
                "delta_rss_mb":-12,"first_total_gc_count":9223372036854775807,
                "first_heap_used_mb":null,"start_time_ms":1.2e3}],
            "name":"session\n\"\\","host_id":"root-host","association":"build"
        }""")
        assertEquals("session\n\"\\", summary.name)
        assertEquals("root-host", summary.hostId)
        assertEquals(3, summary.buildsCount)
        assertEquals(2, summary.buildsSuccessCount)
        assertEquals(1, summary.buildsFailureCount)
        val process = summary.processes.single()
        assertEquals(9007199254740993L, process.pid)
        assertEquals(1200L, process.startTimeMs)
        assertEquals("worker } [ ☺", process.processType)
        assertEquals(-12L, process.deltaRssMb)
        assertEquals(Long.MAX_VALUE, process.firstTotalGcCount)
        assertNull(process.firstHeapUsedMb)
    }

    @Test
    fun `request serialization escapes all controls in keys and values`() {
        val text = (0..31).map(Int::toChar).joinToString("") + "\"\\"
        val root = Json.parse(DaemonitorObservationBuildService.jsonObject(text to text, "pid" to Long.MAX_VALUE)) as JsonObject
        assertEquals(text, root.string(text))
        assertEquals(Long.MAX_VALUE, root.long("pid"))
        assertFails { ObservationSummaryParser.parse("{broken}") }
    }
}
