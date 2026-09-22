package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

/**
 * Application polling use case: collect processes and daemon logs through ports, persist samples
 * and builds through repositories, and correlate builds with [BuildAggregator].
 */
class PollMonitoring(
    private val processSource: ProcessSource,
    private val logSource: DaemonLogSource,
    private val builds: BuildWriter,
    private val samples: ProcessSampleWriter,
    private val aggregator: BuildAggregator,
    private val retentionDays: () -> Long = { RetentionPolicy.DEFAULT.defaultDays },
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

        // Only read tails for daemons we are tracking (live now, or known from a prior poll).
        // Discover may return hundreds of historical daemon-*.out.log paths under ~/.gradle;
        // reading all of them every cycle is too expensive for local IO and catastrophic for
        // GoCoreDaemonLogSource (HTTP tail per path — issue #221).
        val pidsToRead = activeDaemonPids + knownDaemonPids

        for (log in logs) {
            if (log.pid in pidsToRead) {
                val lines = logSource.readNewLines(log)
                if (lines.isNotEmpty()) {
                    lines.flatMap { aggregator.onLogLine(log.pid, it.text, it.event) }.forEach { build ->
                        if (saveIfWithinRetention(build)) inserted = true
                    }
                }
            }
            if (log.pid !in activeDaemonPids) {
                aggregator.onDaemonGone(log.pid)?.let { build ->
                    if (saveIfWithinRetention(build)) inserted = true
                }
            }
        }

        (knownDaemonPids - activeDaemonPids).forEach { gonePid ->
            aggregator.onDaemonGone(gonePid)?.let { build ->
                if (saveIfWithinRetention(build)) inserted = true
            }
        }
        knownDaemonPids = activeDaemonPids
        return inserted
    }

    /**
     * Daemon-log replay can re-emit builds with their original timestamps. Skip anything outside
     * the configured retention window so purge is not undone by the next poll.
     */
    private fun saveIfWithinRetention(build: Build): Boolean {
        val cutoff = RetentionPolicy.DEFAULT.cutoffEpochMs(clock(), retentionDays())
        if (build.startTimeMs < cutoff) return false
        builds.save(build)
        return true
    }

    private fun List<GradleProcess>.activeDaemonPids(): Set<Long> =
        filter { it.type == ProcessType.GRADLE_DAEMON }.map { it.pid }.toSet()
}
