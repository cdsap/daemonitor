package io.github.cdsap.daemonitor.mcp

import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DaemonitorMcpHttpServerTest {

    @Test
    fun `post request returns mcp json response`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() }))
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
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() }))
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
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() }))
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
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() }))
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
        val server = DaemonitorMcpHttpServer.start(0, TOKEN, DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() }))
        try {
            val response = request(server.endpoint, token = TOKEN, method = "GET")

            assertEquals(405, response.statusCode())
            assertNotNull(response.headers().firstValue("Allow").orElse(null))
        } finally {
            server.close()
            db.close()
        }
    }

    private fun post(endpoint: String, token: String, body: String): HttpResponse<String> =
        request(endpoint, token = token, method = "POST", body = body)

    private fun request(
        endpoint: String,
        token: String?,
        method: String,
        body: String = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
        origin: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(endpoint))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (origin != null) builder.header("Origin", origin)
        if (method == "GET") {
            builder.GET()
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
