package io.github.cdsap.daemonitor.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.cdsap.daemonitor.json.Json
import io.github.cdsap.daemonitor.json.JsonNumber
import io.github.cdsap.daemonitor.json.JsonObject
import io.github.cdsap.daemonitor.json.JsonString
import io.github.cdsap.daemonitor.json.JsonValue
import io.github.cdsap.daemonitor.json.jsonObject
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DaemonitorMcpHttpServer private constructor(
    private val httpServer: HttpServer,
    private val executor: ExecutorService,
    val endpoint: String,
) : AutoCloseable {

    override fun close() {
        httpServer.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun start(
            port: Int,
            token: String,
            server: DaemonitorMcpServer,
        ): DaemonitorMcpHttpServer {
            require(port in 0..65_535) { "MCP port must be between 0 and 65535" }
            require(token.isNotBlank()) { "MCP token must not be blank" }

            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            val executor = ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
                { runnable ->
                    Thread(runnable, "daemonitor-mcp-http-${threadIds.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                },
                ThreadPoolExecutor.AbortPolicy(),
            )
            httpServer.executor = executor
            httpServer.createContext("/mcp") { exchange ->
                handle(exchange = exchange, token = token, server = server)
            }
            httpServer.start()

            val actualPort = httpServer.address.port
            return DaemonitorMcpHttpServer(
                httpServer = httpServer,
                executor = executor,
                endpoint = "http://127.0.0.1:$actualPort/mcp",
            )
        }

        private val threadIds = AtomicInteger()

        private fun handle(
            exchange: HttpExchange,
            token: String,
            server: DaemonitorMcpServer,
        ) {
            exchange.use {
                when {
                    !exchange.hostIsAllowed() -> exchange.respondText(403, "Forbidden")
                    !exchange.originIsAllowed() -> exchange.respondText(403, "Forbidden")
                    !exchange.hasToken(token) -> exchange.respondText(
                        status = 401,
                        body = "Unauthorized",
                        headers = mapOf("WWW-Authenticate" to "Bearer"),
                    )
                    exchange.requestMethod.equals("GET", ignoreCase = true) -> exchange.respondText(
                        status = 405,
                        body = "SSE streams are not supported by Daemonitor MCP.",
                        headers = mapOf("Allow" to "POST"),
                    )
                    !exchange.requestMethod.equals("POST", ignoreCase = true) -> exchange.respondText(
                        status = 405,
                        body = "Method not allowed",
                        headers = mapOf("Allow" to "POST"),
                    )
                    else -> exchange.handlePost(server)
                }
            }
        }

        private fun HttpExchange.handlePost(server: DaemonitorMcpServer) {
            val bytes = requestBody.readNBytes(MAX_REQUEST_BYTES + 1)
            if (bytes.size > MAX_REQUEST_BYTES) {
                respondText(413, "Request body too large", headers = mapOf("Connection" to "close"))
                return
            }
            val body = bytes.toString(StandardCharsets.UTF_8)
            val parsed = try {
                Json.parse(body)
            } catch (error: Exception) {
                respondJson(400, responseError(null, -32700, "Parse error: ${error.message}").stringify())
                return
            }
            val request = parsed as? JsonObject
            if (request == null) {
                respondJson(400, responseError(null, -32600, "MCP request must be a JSON object").stringify())
                return
            }

            val id = request.values["id"]
            val response = server.handle(request)
            if (id == null || response == null) {
                respondEmpty(202)
            } else {
                respondJson(200, response.stringify())
            }
        }

        private fun HttpExchange.hostIsAllowed(): Boolean {
            val hosts = requestHeaders["Host"] ?: return false
            if (hosts.size != 1) return false
            val authority = hosts.single()
            val uri = runCatching { URI("http://$authority") }.getOrNull() ?: return false
            if (uri.rawAuthority != authority || authority.endsWith(":") || uri.rawUserInfo != null ||
                !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.port != -1 && uri.port !in 1..65_535)
            ) return false
            // A literal allowlist avoids resolving attacker-controlled DNS names.
            return uri.host.equals("localhost", ignoreCase = true) ||
                uri.host == "127.0.0.1" || uri.host == "[::1]"
        }

        private fun HttpExchange.originIsAllowed(): Boolean {
            val origin = requestHeaders.getFirst("Origin") ?: return true
            val uri = runCatching { URI(origin) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            return host.equals("localhost", ignoreCase = true) ||
                host == "127.0.0.1" ||
                host == "::1" ||
                host == "[::1]"
        }

        private fun HttpExchange.hasToken(expected: String): Boolean {
            val authorization = requestHeaders.getFirst("Authorization")
            val bearer = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization }
            val headerToken = requestHeaders.getFirst("X-Daemonitor-MCP-Token")
            val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
            val bearerMatches = MessageDigest.isEqual(expectedBytes, bearer?.toByteArray(StandardCharsets.UTF_8))
            val headerMatches = MessageDigest.isEqual(expectedBytes, headerToken?.toByteArray(StandardCharsets.UTF_8))
            return bearerMatches || headerMatches
        }

        private fun HttpExchange.respondJson(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            responseHeaders.add("Cache-Control", "no-store")
            responseHeaders.add("Mcp-Protocol-Version", PROTOCOL_VERSION)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun HttpExchange.respondText(
            status: Int,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ) {
            responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            responseHeaders.add("Cache-Control", "no-store")
            headers.forEach { (name, value) -> responseHeaders.add(name, value) }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun HttpExchange.respondEmpty(status: Int) {
            responseHeaders.add("Cache-Control", "no-store")
            responseHeaders.add("Mcp-Protocol-Version", PROTOCOL_VERSION)
            sendResponseHeaders(status, -1)
        }

        private fun responseError(id: JsonValue?, code: Int, message: String): JsonObject =
            jsonObject(
                "jsonrpc" to JsonString("2.0"),
                "id" to id,
                "error" to jsonObject(
                    "code" to JsonNumber(code),
                    "message" to JsonString(message),
                ),
            )

        private const val PROTOCOL_VERSION = "2026-07-28"
        private const val MAX_REQUEST_BYTES = 1024 * 1024
    }
}
