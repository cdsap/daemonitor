package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.DaemonLogLine
import io.github.cdsap.daemonitor.application.DaemonLogSource

/**
 * Experimental [DaemonLogSource] that reads `/v1/daemon-logs` from `daemonitor-cored`
 * over a Unix-domain socket (Go core IPC spike).
 *
 * Build-event correlation stays on the JVM when not using [GoCoreBuildSource]: each poll diffs
 * the Go redacted tail window and returns only the new raw lines.
 *
 * [PollMonitoring] only invokes [readNewLines] for live / previously known Gradle daemon
 * PIDs, so large `~/.gradle/daemon` discovers do not HTTP-tail every historical log (#221).
 */
class GoCoreDaemonLogSource internal constructor(
    private val client: GoCoreClient,
) : DaemonLogSource {
    private val tailTracker = GoCoreLogTailTracker()

    override fun discover(): List<DaemonLog> {
        val body = client.get("/v1/daemon-logs")
        val logs = GoCoreSnapshotParser.parseDaemonLogs(body)
        tailTracker.retainPids(logs.mapTo(mutableSetOf()) { it.pid })
        return logs
    }

    override fun readNewLines(log: DaemonLog): List<DaemonLogLine> {
        val current = runCatching { fetchTailLines(log) }.getOrElse { return emptyList() }
        return tailTracker.newLinesSince(log.pid, current)
    }

    override fun tailFor(log: DaemonLog): List<String> = fetchTailLines(log)

    private fun fetchTailLines(log: DaemonLog): List<String> {
        val query = log.source?.takeIf { it.isNotBlank() }?.let { "?source=$it" }.orEmpty()
        val body = client.get("/v1/daemon-logs/${log.pid}/tail$query")
        return GoCoreSnapshotParser.parseDaemonLogTail(body)
    }
}
