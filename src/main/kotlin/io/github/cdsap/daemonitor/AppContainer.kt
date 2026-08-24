package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.update.DesktopUpdateApplier
import io.github.cdsap.daemonitor.update.DesktopUpdateInstaller
import io.github.cdsap.daemonitor.update.GitHubReleaseUpdateSource
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateInstaller
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
    val updateSource = GitHubReleaseUpdateSource()
    val updateInstaller: UpdateInstaller = DesktopUpdateInstaller()
    val updateApplier: UpdateApplier = DesktopUpdateApplier()

    fun createDesktopService(
        uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): WatcherService = WatcherService(
        runtime = runtime,
        database = database,
        settingsStore = settingsStore,
        clock = clock,
        updateChecker = { updateSource.check(BuildInfo.current.version) },
        updateInstaller = updateInstaller,
        updateApplier = updateApplier,
        mcpServerFactory = ::createMcpServer,
        uiDispatcher = uiDispatcher,
        ioDispatcher = ioDispatcher,
    )

    fun createMcpServer(
        currentProcessesProvider: () -> List<GradleProcess> = processCollector::poll,
    ): DaemonitorMcpServer = DaemonitorMcpServer(
        database = database,
        currentProcessesProvider = currentProcessesProvider,
    )

    override fun close() {
        database.close()
    }
}
