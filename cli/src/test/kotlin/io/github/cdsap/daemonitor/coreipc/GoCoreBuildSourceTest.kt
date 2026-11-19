package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.domain.model.FinalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoCoreBuildSourceTest {
    @Test
    fun `recentBuilds fetches and parses go core builds`() {
        val source = GoCoreBuildSource(
            GoCoreIpc { path ->
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
            },
        )

        val builds = source.recentBuilds(limit = 50)
        assertEquals(listOf("b1"), builds.map { it.buildId })
        assertEquals(FinalStatus.FAILED, builds.single().finalStatus)
    }

    @Test
    fun `daemon builds use the core lifetime query`() {
        val source = GoCoreBuildSource(
            GoCoreIpc { path ->
                assertEquals("/v1/builds?pid=9&start_time_ms=200&limit=20", path)
                """{"builds": []}"""
            },
        )

        assertEquals(emptyList(), source.buildsForDaemon(9, 200, 20))
        assertFailsWith<IllegalArgumentException> { source.buildsForDaemon(9, 0, 20) }
    }
}
