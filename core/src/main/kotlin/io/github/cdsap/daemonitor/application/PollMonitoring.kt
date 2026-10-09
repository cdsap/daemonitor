package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

/**
 * Application polling use case: collect processes and daemon logs through ports, persist samples,
 * and delegate build processing to the mode selected by the composition root.
 *
 * [buildProcessingMode] owns the local aggregation, remote import, or shared-core behavior.
 */
class PollMonitoring(
    private val processSource: ProcessSource,
    private val logSource: DaemonLogSource,
    private val samples: ProcessSampleWriter,
    private val buildProcessingMode: BuildProcessingMode,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Compatibility constructor for direct application-layer callers. */
    constructor(
        processSource: ProcessSource,
        logSource: DaemonLogSource,
        builds: BuildWriter,
        samples: ProcessSampleWriter,
        aggregator: BuildAggregator,
        mode: MonitoringMode = MonitoringMode.Local,
        retentionDays: () -> Long = { RetentionPolicy.DEFAULT.defaultDays },
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        processSource = processSource,
        logSource = logSource,
        samples = samples,
        buildProcessingMode = mode.buildProcessingMode(
            builds = builds,
            logSource = logSource,
            aggregator = aggregator,
            retentionDays = retentionDays,
            clock = clock,
        ),
        clock = clock,
    )

    data class PollResult(
        val processes: List<GradleProcess>,
        val daemonLogs: List<DaemonLog>,
        val buildsChanged: Boolean,
    )

    fun pollOnce(): PollResult {
        val now = clock()
        val processes = processSource.currentProcesses()
        if (buildProcessingMode.persistsSamples) {
            processes.forEach { samples.save(it, now) }
        }

        val logs = logSource.discover()
        val buildsChanged = buildProcessingMode.process(
            logs = logs,
            activeDaemonPids = processes.activeDaemonPids(),
        )
        return PollResult(
            processes = processes,
            daemonLogs = logs,
            buildsChanged = buildsChanged,
        )
    }

    internal fun processForBuilds(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean =
        buildProcessingMode.process(logs, activeDaemonPids)

    fun tailFor(logs: List<DaemonLog>, pid: Long): DaemonLogTailResult {
        val log = logs.firstOrNull { it.pid == pid } ?: return DaemonLogTailResult.NoLog
        return runCatching { DaemonLogTailResult.Lines(logSource.tailFor(log)) }
            .getOrElse { error ->
                DaemonLogTailResult.Error(error::class.simpleName ?: "UnknownError")
            }
    }

    private fun List<GradleProcess>.activeDaemonPids(): Set<Long> =
        filter { it.type == ProcessType.GRADLE_DAEMON }.map { it.pid }.toSet()
}
