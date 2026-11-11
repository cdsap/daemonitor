package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoCoreClientTest {
    @Test
    fun `host context requests use separate endpoints`() {
        val socket = Files.createTempFile("daemonitor-context", ".sock")
        try {
            val paths = mutableListOf<String>()
            val source = GoCoreProcessSource(socket) { _, path ->
                paths += path
                when (path) {
                    "/v1/host" -> """{"sampled_at_ms":1}"""
                    "/v1/capabilities" -> """{"metrics":[]}"""
                    else -> error("unexpected endpoint")
                }
            }
            assertEquals(emptyList(), paths)
            source.host()
            source.capabilities()
            assertEquals(listOf("/v1/host", "/v1/capabilities"), paths)
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `fetches endpoint through the shared client`() {
        val socket = Files.createTempFile("daemonitor-core-client", ".sock")
        try {
            val client = GoCoreClient(socket) { _, path -> "response for $path" }

            assertEquals("response for /v1/processes", client.get("/v1/processes"))
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `missing socket is rejected before injected fetch`() {
        val socket = Files.createTempFile("daemonitor-core-client-missing", ".sock")
        Files.deleteIfExists(socket)
        val client = GoCoreClient(socket) { _, _ -> error("should not fetch") }

        assertFailsWith<GoCoreUnavailableException> { client.get("/v1/health") }
    }
}
