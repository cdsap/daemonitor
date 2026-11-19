package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        val error = assertFailsWith<GoCoreUnavailableException> { client.get("/v1/health") }
        assertTrue(error.message!!.contains("socket not found"))
        assertTrue(error.message!!.contains("daemonitor-cored"))
    }

    @Test
    fun `stale socket file fails as unreachable`() {
        val socket = Files.createTempFile("daemonitor-core-stale", ".sock")
        try {
            // Regular file is not a listening AF_UNIX server — connect must fail clearly.
            val error = assertFailsWith<GoCoreUnavailableException> {
                unixHttpGet(socket, "/v1/health")
            }
            assertTrue(error.message!!.contains("unreachable") || error.message!!.contains("stale"))
            assertTrue(error.message!!.contains(socket.toString()))
        } finally {
            Files.deleteIfExists(socket)
        }
    }
}
