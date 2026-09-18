package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.collect.DaemonLog
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

/**
 * Application polling use case: collect processes and daemon logs through ports, persist samples
 * and builds through repositories, and correlate builds with [BuildAggregator].
 */
class PollMonitoring(
    private val processSource: ProcessSource,
    private val logSource: DaemonLogSource,
    private val builds: BuildRepository,
    private val samples: ProcessSampleRepository,
    private val aggregator: BuildAggregator,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var knownDaemonPids = emptySet<Long>()

    data class PollResult(
        val processes: List<GradleProcess>,
        val daemonLogs: List<DaemonLog>,
        val buildsChanged: Boolean,
    )

    fun pollOnce(): PollResult {
        val now = clock()
        val processes = processSource.currentProcesses()
        processes.forEach { samples.save(it, now) }

        val logs = logSource.discover()
        return PollResult(
            processes = processes,
            daemonLogs = logs,
            buildsChanged = processForBuilds(logs, activeDaemonPids = processes.activeDaemonPids()),
        )
    }

    fun tailFor(logs: List<DaemonLog>, pid: Long): List<String> =
        logs.firstOrNull { it.pid == pid }?.let { logSource.tailFor(it) }.orEmpty()

    internal fun processForBuilds(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean {
        var inserted = false

        for (log in logs) {
            val lines = logSource.readNewLines(log)
            if (lines.isNotEmpty()) {
                lines.flatMap { aggregator.onLogLine(log.pid, it.text, it.event) }.forEach {
                    builds.save(it)
                    inserted = true
                }
            }
            if (log.pid !in activeDaemonPids) {
                aggregator.onDaemonGone(log.pid)?.let {
                    builds.save(it)
                    inserted = true
                }
            }
        }

        (knownDaemonPids - activeDaemonPids).forEach { gonePid ->
            aggregator.onDaemonGone(gonePid)?.let {
                builds.save(it)
                inserted = true
            }
        }
        knownDaemonPids = activeDaemonPids
        return inserted
    }

    private fun List<GradleProcess>.activeDaemonPids(): Set<Long> =
        filter { it.type == ProcessType.GRADLE_DAEMON }.map { it.pid }.toSet()
}
