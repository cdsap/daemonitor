package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.BuildSource
import io.github.cdsap.daemonitor.application.PollMonitoring
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.application.BuildWriter
import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.ProcessSampleWriter
import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess

/**
 * UI-independent collection and persistence runtime shared by desktop and headless launchers.
 *
 * This facade preserves the runtime API used by desktop services while delegating polling to the
 * application-layer [PollMonitoring] use case.
 */
class WatcherRuntime(
    processSource: ProcessSource,
    logSource: DaemonLogSource,
    builds: BuildWriter,
    samples: ProcessSampleWriter,
    aggregator: BuildAggregator,
    buildSource: BuildSource? = null,
    retentionDays: () -> Long = { RetentionPolicy.DEFAULT.defaultDays },
    clock: () -> Long = System::currentTimeMillis,
) {
    private val monitoring = PollMonitoring(
        processSource = processSource,
        logSource = logSource,
        builds = builds,
        samples = samples,
        aggregator = aggregator,
        buildSource = buildSource,
        retentionDays = retentionDays,
        clock = clock,
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
}
