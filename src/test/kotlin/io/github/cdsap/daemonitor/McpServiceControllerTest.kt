package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpHttpServer
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServiceControllerTest {
    @Test
    fun `start and stop manage a single server instance`(@TempDir tmp: Path) {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        try {
            val controller = McpServiceController { port, token ->
                DaemonitorMcpHttpServer.start(
                    port = port,
                    token = token,
                    server = DaemonitorMcpServer(
                        DefaultDaemonitorQueryService(database, ProcessSource { emptyList() }),
                    ),
                )
            }

            val first = controller.start(port = 0, token = "test-token").getOrThrow()
            assertTrue(controller.isRunning)
            assertEquals(first, controller.endpoint)

            val second = controller.start(port = 0, token = "test-token").getOrThrow()
            assertEquals(first, second)

            controller.stop()
            assertFalse(controller.isRunning)
            assertEquals(null, controller.endpoint)
        } finally {
            database.close()
        }
    }

    @Test
    fun `start failure leaves controller stopped`(@TempDir tmp: Path) {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        try {
            val controller = McpServiceController { _, _ ->
                error("bind failed")
            }
            val result = controller.start(port = 1, token = "token")
            assertTrue(result.isFailure)
            assertFalse(controller.isRunning)
        } finally {
            database.close()
        }
    }
}
