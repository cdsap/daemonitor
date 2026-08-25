package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.persistence.Settings
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppContainerTest {
    @Test
    fun `container owns concrete infrastructure wiring`(@TempDir tmp: Path) {
        AppContainer(
            databasePath = tmp.resolve("watcher.db"),
            settingsPath = tmp.resolve("settings.properties"),
        ).use { container ->
            assertIs<ProcessCollector>(container.processCollector)
            assertIs<DaemonLogWatcher>(container.daemonLogWatcher)
            assertIs<WatcherDatabase>(container.database)
            assertIs<SettingsStore>(container.settingsStore)
            assertIs<BuildAggregator>(container.buildAggregator)
            assertIs<WatcherRuntime>(container.runtime)
            assertIs<UpdateService>(container.updateService)

            assertIs<WatcherService>(container.createDesktopService())
            assertIs<DaemonitorMcpServer>(
                container.createMcpServer(currentProcessesProvider = { emptyList() }),
            )
        }
    }

    @Test
    fun `container settings store uses configured path`(@TempDir tmp: Path) {
        val settingsPath = tmp.resolve("settings.properties")
        AppContainer(
            databasePath = tmp.resolve("watcher.db"),
            settingsPath = settingsPath,
        ).use { container ->
            container.settingsStore.save(Settings(retentionDays = 30))
            assertEquals(30L, SettingsStore(settingsPath).load().retentionDays)
        }
    }

    @Test
    fun `mcp stdio bootstrapper accepts a composition root`(@TempDir tmp: Path) {
        // DaemonitorMcpStdio.run takes ownership and closes the container.
        io.github.cdsap.daemonitor.mcp.DaemonitorMcpStdio.run(
            container = AppContainer(
                databasePath = tmp.resolve("watcher.db"),
                settingsPath = tmp.resolve("settings.properties"),
            ),
            input = java.io.ByteArrayInputStream(ByteArray(0)),
            output = java.io.ByteArrayOutputStream(),
        )
    }
}
