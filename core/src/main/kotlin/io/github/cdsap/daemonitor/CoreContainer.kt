package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.application.MonitoringMode
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.persistence.RetentionRepository
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
    processSource: ProcessSource? = null,
    logSource: DaemonLogSource? = null,
    mode: MonitoringMode = MonitoringMode.Local,
) : AutoCloseable {
    val processSource: ProcessSource = processSource ?: ProcessCollector()
    val daemonLogSource: DaemonLogSource = logSource ?: DaemonLogWatcher()
    val database = WatcherDatabase.open(databasePath)
    val retentionRepository: RetentionRepository = database
    val settingsStore = SettingsStore(settingsPath)
    val settingsService = SettingsService(settingsStore, database, clock)
    val buildAggregator = BuildAggregator(
        sampleProvider = database,
        ambientEnvNames = ambientEnvNames,
        logSnippetLimit = with(MonitoringConfig.DEFAULT.logSnippetLimit) {
            BuildAggregator.LogSnippetLimit(lines = lines, chars = chars)
        },
    )
    val runtime = WatcherRuntime(
        processSource = this.processSource,
        logSource = this.daemonLogSource,
        aggregator = buildAggregator,
        builds = database,
        samples = database,
        mode = mode,
        retentionDays = { settingsStore.load().retentionDays },
        clock = clock,
    )

    override fun close() {
        database.close()
    }
}
