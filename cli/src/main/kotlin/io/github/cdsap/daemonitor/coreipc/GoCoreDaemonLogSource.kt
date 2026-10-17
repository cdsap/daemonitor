package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.DaemonLogLine
import io.github.cdsap.daemonitor.application.DaemonLogSource
import java.nio.file.Path

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
    constructor(socketPath: Path, fetch: (Path, String) -> String = ::unixHttpGet) : this(
        GoCoreClient(socketPath, fetch),
    )

    private val tailTracker = GoCoreLogTailTracker()

    override fun discover(): List<DaemonLog> {
        val body = client.get("/v1/daemon-logs")
        return GoCoreSnapshotParser.parseDaemonLogs(body)
    }

    override fun readNewLines(log: DaemonLog): List<DaemonLogLine> {
        val current = runCatching { fetchTailLines(log.pid) }.getOrElse { return emptyList() }
        return tailTracker.newLinesSince(log.pid, current).map(::DaemonLogLine)
    }

    override fun tailFor(log: DaemonLog): List<String> = fetchTailLines(log.pid)

    private fun fetchTailLines(pid: Long): List<String> {
        val body = client.get("/v1/daemon-logs/$pid/tail")
        return GoCoreSnapshotParser.parseDaemonLogTail(body)
    }
}
