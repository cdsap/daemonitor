package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.BuildSource
import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.distribution.DistributionChannel
import io.github.cdsap.daemonitor.infrastructure.update.updateServiceForDistribution
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Application composition root. Delegates shared monitoring infrastructure to [CoreContainer]
 * and keeps desktop-specific services, distribution, and update wiring here so entry points
 * stay thin bootstrappers.
 */
class AppContainer(
    databasePath: Path = AppDirectories.system.databasePath,
    settingsPath: Path = AppDirectories.system.settingsPath,
    private val clock: () -> Long = System::currentTimeMillis,
    ambientEnvNames: Set<String> = System.getenv().keys.toSet(),
    distribution: DistributionChannel = BuildInfo.current.distribution,
    processSource: ProcessSource? = null,
    logSource: DaemonLogSource? = null,
    buildSource: BuildSource? = null,
    persistSamples: Boolean = true,
) : AutoCloseable {
    private val core = CoreContainer(
        databasePath = databasePath,
        settingsPath = settingsPath,
        clock = clock,
        ambientEnvNames = ambientEnvNames,
        processSource = processSource,
        logSource = logSource,
        buildSource = buildSource,
        persistSamples = persistSamples,
    )

    val processCollector: ProcessCollector = core.processCollector
    val daemonLogWatcher: DaemonLogWatcher = core.daemonLogWatcher
    val database: WatcherDatabase = core.database
    val settingsStore: SettingsStore = core.settingsStore
    val buildAggregator: BuildAggregator = core.buildAggregator
    val runtime: WatcherRuntime = core.runtime
    /** Live process source actually used by [runtime] (Go core or JVM collector). */
    val liveProcessSource: ProcessSource = processSource ?: processCollector

    val distributionChannel: DistributionChannel = distribution
    val updateService: UpdateService = updateServiceForDistribution(distribution)

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
        currentProcessesProvider: () -> List<GradleProcess> = { liveProcessSource.currentProcesses() },
    ): DaemonitorMcpServer {
        val builds: BuildRepository = database
        val samples: ProcessSampleRepository = database
        return DaemonitorMcpServer(
            DefaultDaemonitorQueryService(
                builds = builds,
                samples = samples,
                processSource = ProcessSource { currentProcessesProvider() },
            ),
        )
    }

    override fun close() {
        core.close()
    }
}
