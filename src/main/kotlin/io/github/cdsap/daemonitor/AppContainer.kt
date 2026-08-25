package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.infrastructure.update.defaultUpdateService
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Application composition root. Owns concrete infrastructure wiring so entry points stay thin
 * bootstrappers and application/presentation code receives dependencies through constructors.
 */
class AppContainer(
    databasePath: Path = AppDirectories.system.databasePath,
    settingsPath: Path = AppDirectories.system.settingsPath,
    private val clock: () -> Long = System::currentTimeMillis,
    ambientEnvNames: Set<String> = System.getenv().keys.toSet(),
) : AutoCloseable {
    val processCollector = ProcessCollector()
    val daemonLogWatcher = DaemonLogWatcher()
    val database = WatcherDatabase.open(databasePath)
    val settingsStore = SettingsStore(settingsPath)
    val buildAggregator = BuildAggregator(
        sampleProvider = database::samplesInWindow,
        ambientEnvNames = ambientEnvNames,
    )
    val runtime = WatcherRuntime(
        collector = processCollector,
        logWatcher = daemonLogWatcher,
        aggregator = buildAggregator,
        database = database,
        clock = clock,
    )
    val updateService: UpdateService = defaultUpdateService()

    fun createDesktopService(
        uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): WatcherService = WatcherService(
        runtime = runtime,
        historyService = HistoryService(database),
        settingsService = SettingsService(settingsStore, database, clock),
        mcpController = McpServiceController.create(::createMcpServer),
        updateService = updateService,
        monitoringService = MonitoringService(
            pollAction = { runtime.pollOnce() },
            ioDispatcher = ioDispatcher,
            pollInterval = MonitoringConfig.DEFAULT.pollInterval,
        ),
        uiDispatcher = uiDispatcher,
        clock = clock,
    )

    fun createMcpServer(
        currentProcessesProvider: () -> List<GradleProcess> = processCollector::poll,
    ): DaemonitorMcpServer = DaemonitorMcpServer(
        DefaultDaemonitorQueryService(
            database = database,
            processSource = ProcessSource { currentProcessesProvider() },
        ),
    )

    override fun close() {
        database.close()
    }
}
