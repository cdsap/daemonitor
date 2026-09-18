package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import java.nio.file.Path

/** Shared non-UI runtime wiring used by the desktop app and standalone CLI. */
class CoreContainer(
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
        logSnippetLimit = with(MonitoringConfig.DEFAULT.logSnippetLimit) {
            BuildAggregator.LogSnippetLimit(lines = lines, chars = chars)
        },
    )
    val runtime = WatcherRuntime(
        collector = processCollector,
        logWatcher = daemonLogWatcher,
        aggregator = buildAggregator,
        database = database,
        clock = clock,
    )

    override fun close() {
        database.close()
    }
}
