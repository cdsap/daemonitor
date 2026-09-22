package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.domain.model.FinalStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoCoreBuildSourceTest {
    @Test
    fun `recentBuilds fetches and parses go core builds`() {
        val socket = Files.createTempFile("daemonitor-core", ".sock")
        Files.deleteIfExists(socket)
        Files.createFile(socket)
        try {
            val source = GoCoreBuildSource(socketPath = socket) { _, path ->
                assertEquals("/v1/builds?limit=50", path)
                """
                {
                  "count": 1,
                  "builds": [
                    {
                      "build_id": "b1",
                      "daemon_pid": 9,
                      "start_time_ms": 10,
                      "inferred_source": "TERMINAL",
                      "final_status": "FAILED"
                    }
                  ]
                }
                """.trimIndent()
            }

            val builds = source.recentBuilds(limit = 50)
            assertEquals(listOf("b1"), builds.map { it.buildId })
            assertEquals(FinalStatus.FAILED, builds.single().finalStatus)
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `missing socket fails clearly`() {
        val socket = Files.createTempFile("daemonitor-core-missing", ".sock")
        Files.deleteIfExists(socket)
        val source = GoCoreBuildSource(socketPath = socket) { _, _ -> error("should not fetch") }
        assertFailsWith<IllegalArgumentException> { source.recentBuilds() }
    }
}
