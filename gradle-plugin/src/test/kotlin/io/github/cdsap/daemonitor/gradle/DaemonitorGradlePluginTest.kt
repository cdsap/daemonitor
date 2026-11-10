package io.github.cdsap.daemonitor.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
