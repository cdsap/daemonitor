package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoCoreClientTest {
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
