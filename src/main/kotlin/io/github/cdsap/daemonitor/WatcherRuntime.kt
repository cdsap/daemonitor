package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLog
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.store.WatcherDatabase

/** UI-independent collection and persistence runtime shared by desktop and headless launchers. */
class WatcherRuntime(
    private val collector: ProcessCollector,
    private val logWatcher: DaemonLogWatcher,
    private val aggregator: BuildAggregator,
    private val database: WatcherDatabase,
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
        val processes = collector.poll()
        processes.forEach { database.insertSample(it, now) }

        val logs = logWatcher.discover()
        return PollResult(
            processes = processes,
            daemonLogs = logs,
            buildsChanged = processForBuilds(logs, activeDaemonPids = processes.activeDaemonPids()),
        )
    }

    fun tailFor(logs: List<DaemonLog>, pid: Long): List<String> =
        logs.firstOrNull { it.pid == pid }?.let { logWatcher.tailFor(it.path) }.orEmpty()

    internal fun processForBuilds(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean {
        var inserted = false

        for (log in logs) {
            val lines = logWatcher.readNewLines(log.path)
            if (lines.isNotEmpty()) {
                lines.flatMap { aggregator.onLogLine(log.pid, it.text, it.event) }.forEach {
                    database.insertBuild(it)
                    inserted = true
                }
            }
            if (log.pid !in activeDaemonPids) {
                aggregator.onDaemonGone(log.pid)?.let {
                    database.insertBuild(it)
                    inserted = true
                }
            }
        }

        (knownDaemonPids - activeDaemonPids).forEach { gonePid ->
            aggregator.onDaemonGone(gonePid)?.let {
                database.insertBuild(it)
                inserted = true
            }
        }
        knownDaemonPids = activeDaemonPids
        return inserted
    }

    private fun List<GradleProcess>.activeDaemonPids(): Set<Long> =
        filter { it.type == ProcessType.GRADLE_DAEMON }.map { it.pid }.toSet()

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
