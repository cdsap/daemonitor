package io.github.cdsap.daemonitor.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "daemonitor-mcp-http-${threadIds.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
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
            val body = requestBody.readBytes().toString(StandardCharsets.UTF_8)
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
            return bearer == expected || headerToken == expected
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
    }
}
