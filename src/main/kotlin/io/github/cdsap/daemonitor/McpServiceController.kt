package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.mcp.DaemonitorMcpHttpServer
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.store.WatcherDatabase

/** Controls the optional MCP HTTP server lifecycle independently of UI wiring. */
class McpServiceController(
    private val createServer: (port: Int, token: String) -> DaemonitorMcpHttpServer,
) {
    @Volatile
    private var server: DaemonitorMcpHttpServer? = null

    val endpoint: String?
        get() = server?.endpoint

    val isRunning: Boolean
        get() = server != null

    fun start(port: Int, token: String): Result<String> {
        val existing = server
        if (existing != null) return Result.success(existing.endpoint)
        return runCatching {
            val started = createServer(port, token)
            server = started
            started.endpoint
        }.onFailure {
            server = null
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    companion object {
        fun create(database: WatcherDatabase): McpServiceController =
            McpServiceController { port, token ->
                DaemonitorMcpHttpServer.start(
                    port = port,
                    token = token,
                    server = DaemonitorMcpServer(database),
                )
            }
    }
}
