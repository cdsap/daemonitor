package io.github.cdsap.daemonitor.mcp

import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DaemonitorMcpHttpServerTest {

    @Test
    fun `post body limit applies to fixed length and chunked requests`(@TempDir tmp: Path) {
        WatcherDatabase.open(tmp.resolve("watcher.db")).use { db ->
            DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db)).use { server ->
                val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
                val limit = 1024 * 1024
                for (chunked in listOf(false, true)) {
                    val body = ping.padEnd(limit, ' ')
                    assertEquals(200, request(server.endpoint, TOKEN, "POST", body, chunked = chunked).statusCode())
                    assertEquals(413, request(server.endpoint, TOKEN, "POST", body + " ", chunked = chunked).statusCode())
                    val multibyteBody = ping.padEnd(limit - 1, ' ') + "é"
                    assertEquals(413, request(server.endpoint, TOKEN, "POST", multibyteBody, chunked = chunked).statusCode())
                }
                assertEquals(200, post(server.endpoint, TOKEN, ping).statusCode())
            }
        }
    }

    @Test
    fun `host must be a single loopback authority`(@TempDir tmp: Path) {
        WatcherDatabase.open(tmp.resolve("watcher.db")).use { db ->
            DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db)).use { server ->
                val port = URI(server.endpoint).port
                for (host in listOf("127.0.0.1", "localhost", "LOCALHOST:$port", "127.0.0.1:$port", "[::1]", "[::1]:$port")) {
                    assertEquals(200, rawStatus(port, listOf(host)), host)
                }
                for (hosts in listOf(
                    listOf("example.com"), listOf("localhost.example.com"), listOf("127.0.0.2"),
                    listOf("example.com@localhost"), listOf("localhost/path"), listOf("localhost#fragment"),
                    listOf("localhost?query"), listOf("localhost:invalid"), listOf("localhost:65536"), listOf("localhost:"),
                    listOf("localhost", "example.com"), listOf("localhost", "localhost"),
                )) {
                    assertEquals(403, rawStatus(port, hosts), hosts.toString())
                }
                // HTTP/1.0 permits a missing Host header at the transport level.
                assertEquals(403, rawStatus(port, emptyList()))
            }
        }
    }

    @Test
    fun `both token headers accept only matching tokens`(@TempDir tmp: Path) {
        WatcherDatabase.open(tmp.resolve("watcher.db")).use { db ->
            DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db)).use { server ->
                for (token in listOf("wrong-token", TOKEN + "x", TOKEN.dropLast(1))) {
                    assertEquals(401, request(server.endpoint, token, "POST").statusCode())
                    assertEquals(401, request(server.endpoint, null, "POST", headerToken = token).statusCode())
                }
                assertEquals(200, request(server.endpoint, null, "POST", headerToken = TOKEN).statusCode())
            }
        }
    }

    private fun rawStatus(port: Int, hosts: List<String>): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            val headers = hosts.joinToString("") { "Host: $it\r\n" }
            socket.getOutputStream().write(
                ("POST /mcp HTTP/1.0\r\n" + headers + "Authorization: Bearer $TOKEN\r\n" +
                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray(),
            )
            socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
        }

    @Test
    fun `post request returns mcp json response`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db))
        try {
            val response = post(
                endpoint = server.endpoint,
                token = TOKEN,
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28"}}""",
            )

            assertEquals(200, response.statusCode())
            val json = Json.parse(response.body()) as JsonObject
            assertEquals("daemonitor", json.obj("result")!!.obj("serverInfo")!!.string("name"))
        } finally {
            server.close()
            db.close()
        }
    }

    @Test
    fun `post request requires token`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db))
        try {
            val response = request(server.endpoint, token = null, method = "POST")

            assertEquals(401, response.statusCode())
        } finally {
            server.close()
            db.close()
        }
    }

    @Test
    fun `browser origins must be loopback`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db))
        try {
            val response = request(
                endpoint = server.endpoint,
                token = TOKEN,
                method = "POST",
                origin = "https://example.com",
            )

            assertEquals(403, response.statusCode())
        } finally {
            server.close()
            db.close()
        }
    }

    @Test
    fun `notifications return accepted without body`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db))
        try {
            val response = post(
                endpoint = server.endpoint,
                token = TOKEN,
                body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            )

            assertEquals(202, response.statusCode())
            assertEquals("", response.body())
        } finally {
            server.close()
            db.close()
        }
    }

    @Test
    fun `get request is rejected because sse is not supported`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, mcpServer(db))
        try {
            val response = request(server.endpoint, token = TOKEN, method = "GET")

            assertEquals(405, response.statusCode())
            assertNotNull(response.headers().firstValue("Allow").orElse(null))
        } finally {
            server.close()
            db.close()
        }
    }

    private fun mcpServer(db: WatcherDatabase): DaemonitorMcpServer =
        DaemonitorMcpServer(DefaultDaemonitorQueryService(db, db, ProcessSource { emptyList() }))

    private fun post(endpoint: String, token: String, body: String): HttpResponse<String> =
        request(endpoint, token = token, method = "POST", body = body)

    private fun request(
        endpoint: String,
        token: String?,
        method: String,
        body: String = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
        origin: String? = null,
        chunked: Boolean = false,
        headerToken: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (origin != null) builder.header("Origin", origin)
        if (headerToken != null) builder.header("X-Daemonitor-MCP-Token", headerToken)
        if (method == "GET") {
            builder.GET()
        } else {
            val publisher = if (chunked) {
                HttpRequest.BodyPublishers.ofInputStream { body.byteInputStream() }
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            }
            builder.method(method, publisher)
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
