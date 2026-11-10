package io.github.cdsap.daemonitor.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonitorGradlePluginTest {
    @Test
    fun `payload includes escaped host and Gradle JVM fields`() {
        val payload = DaemonitorObservationBuildService.jsonObject(
            "host_id" to "host\\name\"quoted",
            "gradle_jvm_pid" to 42,
            "gradle_jvm_start_time_ms" to 123L,
        )
        assertTrue(payload.contains("\"host_id\":\"host\\\\name\\\"quoted\""))
        assertTrue(payload.contains("\"gradle_jvm_pid\":42"))
        assertEquals('{', payload.first())
        assertEquals('}', payload.last())
    }

    @Test
    fun `authoritative close summary is written to the deterministic build report`() {
        val project = Files.createTempDirectory("daemonitor-report-test")
        try {
            val summary = """{"schema_version":"v1","total_peak_rss_mb":512,"processes":[{"peak_rss_mb":512}]}"""
            ObservationReportSink(project).writeIfPresent(summary)

            val report = project.resolve("build/daemonitor/observation-summary.json")
            assertTrue(Files.isRegularFile(report))
            assertEquals(summary, Files.readString(report))
        } finally {
            deleteRecursively(project)
        }
    }

    @Test
    fun `skipped measurement does not create a report`() {
        val project = Files.createTempDirectory("daemonitor-report-skipped-test")
        try {
            ObservationReportSink(project).writeIfPresent(null)
            assertFalse(Files.exists(project.resolve("build")))
        } finally {
            deleteRecursively(project)
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
