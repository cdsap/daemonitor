package io.github.cdsap.daemonitor.coreipc

import kotlin.test.Test
import kotlin.test.assertEquals

class GoCoreProcessSourceTest {
    @Test
    fun `host context requests use separate endpoints`() {
        val paths = mutableListOf<String>()
        val source = GoCoreProcessSource(
            GoCoreIpc { path ->
                paths += path
                when (path) {
                    "/v1/host" -> """{"sampled_at_ms":1}"""
                    "/v1/capabilities" -> """{"metrics":[]}"""
                    else -> error("unexpected endpoint")
                }
            },
        )

        assertEquals(emptyList(), paths)
        assertEquals(1L, source.host().sampledAtMs)
        assertEquals(emptyList(), source.capabilities())
        assertEquals(listOf("/v1/host", "/v1/capabilities"), paths)
    }

    @Test
    fun `current processes fetches and parses go core processes`() {
        val source = GoCoreProcessSource(
            GoCoreIpc { path ->
                assertEquals("/v1/processes", path)
                """{"processes":[{"pid":7,"type":"GRADLE_DAEMON"}]}"""
            },
        )

        assertEquals(listOf(7L), source.currentProcesses().map { it.pid })
    }
}
