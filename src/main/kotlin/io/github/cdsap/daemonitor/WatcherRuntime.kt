package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.PollMonitoring
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.application.BuildRepository
import io.github.cdsap.daemonitor.application.ProcessSampleRepository
import io.github.cdsap.daemonitor.collect.DaemonLog
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.store.WatcherDatabase

/**
 * UI-independent collection and persistence runtime shared by desktop and headless launchers.
 *
 * This facade preserves the runtime API used by desktop services while delegating polling to the
 * application-layer [PollMonitoring] use case.
 */
class WatcherRuntime(
    private val monitoring: PollMonitoring,
) {
    constructor(
        collector: ProcessSource,
        logWatcher: DaemonLogSource,
        aggregator: BuildAggregator,
        database: WatcherDatabase,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        PollMonitoring(
            processSource = collector,
            logSource = logWatcher,
            builds = database,
            samples = database,
            aggregator = aggregator,
            clock = clock,
        ),
    )

    constructor(
        processSource: ProcessSource,
        logSource: DaemonLogSource,
        builds: BuildRepository,
        samples: ProcessSampleRepository,
        aggregator: BuildAggregator,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        PollMonitoring(
            processSource = processSource,
            logSource = logSource,
            builds = builds,
            samples = samples,
            aggregator = aggregator,
            clock = clock,
        ),
    )

    data class PollResult(
        val processes: List<GradleProcess>,
        val daemonLogs: List<DaemonLog>,
        val buildsChanged: Boolean,
    )

    fun pollOnce(): PollResult = monitoring.pollOnce().toRuntimeResult()

    fun tailFor(logs: List<DaemonLog>, pid: Long): List<String> =
        monitoring.tailFor(logs, pid)

    internal fun processForBuilds(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean =
        monitoring.processForBuilds(logs, activeDaemonPids)

    private fun PollMonitoring.PollResult.toRuntimeResult(): PollResult = PollResult(
        processes = processes,
        daemonLogs = daemonLogs,
        buildsChanged = buildsChanged,
    )

    companion object {
        /** Test/helper factory. Production wiring lives in [AppContainer]. */
        fun create(database: WatcherDatabase): WatcherRuntime = WatcherRuntime(
            collector = ProcessCollector(),
            logWatcher = DaemonLogWatcher(),
            aggregator = BuildAggregator(
                sampleProvider = database::samplesInWindow,
                ambientEnvNames = System.getenv().keys.toSet(),
            ),
            database = database,
        )
    }
}
