package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoCoreClientTest {
    @Test
    fun `accepts existing 200 status line patterns`() {
        val statusLines = listOf("HTTP/1.0 200 OK", "HTTP/1.1 200 OK", "HTTP/1.1 200", "custom 200 result")

        for (statusLine in statusLines) {
            assertEquals("body", parseGoCoreHttpResponse("$statusLine\r\nContent-Type: text/plain\r\n\r\nbody"))
        }
    }

    @Test
    fun `returns an empty response body`() {
        assertEquals("", parseGoCoreHttpResponse("HTTP/1.0 200 OK\r\n\r\n"))
    }

    @Test
    fun `preserves multiline body after the first header separator`() {
        val body = "  first line\r\nsecond line\n\r\n\r\nlast line  \n"

        assertEquals(body, parseGoCoreHttpResponse("HTTP/1.1 200 OK\r\nX-Test: value\r\n\r\n$body"))
    }

    @Test
    fun `rejects missing CRLF header separator before checking status`() {
        val responses = listOf("", "HTTP/1.0 200 OK", "HTTP/1.1 200 OK\n\nbody", "HTTP/1.1 500 Error")

        for (response in responses) {
            val error = assertFailsWith<IllegalArgumentException> { parseGoCoreHttpResponse(response) }
            assertEquals("Go core returned a malformed HTTP response", error.message)
        }
    }

    @Test
    fun `rejects status lines outside existing 200 patterns`() {
        val statusLines = listOf(
            "HTTP/1.1 500 Internal Server Error",
            "HTTP/1.1 201 Created",
            "HTTP/1.1 2000 OK",
            "HTTP/1.1 200\tOK",
            "",
        )

        for (statusLine in statusLines) {
            val error = assertFailsWith<IllegalArgumentException> {
                parseGoCoreHttpResponse("$statusLine\r\n\r\nbody")
            }
            assertEquals("Go core request failed: $statusLine", error.message)
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
