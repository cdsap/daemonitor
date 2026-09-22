package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.collect.DaemonLog
import io.github.cdsap.daemonitor.collect.DaemonLogLine
import io.github.cdsap.daemonitor.collect.DaemonLogParser
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Experimental [DaemonLogSource] that reads `/v1/daemon-logs` from `daemonitor-cored`
 * over a Unix-domain socket (Go core IPC spike).
 *
 * Build-event correlation stays on the JVM: each poll diffs the Go redacted tail window
 * and feeds new lines through [DaemonLogParser].
 */
class GoCoreDaemonLogSource(
    private val socketPath: Path,
    private val fetch: (Path, String) -> String = ::unixHttpGet,
) : DaemonLogSource {
    private val lastTails = mutableMapOf<Long, List<String>>()

    override fun discover(): List<DaemonLog> {
        require(socketPath.exists()) {
            "Go core socket not found: $socketPath (is daemonitor-cored running?)"
        }
        val body = fetch(socketPath, "/v1/daemon-logs")
        return GoCoreSnapshotParser.parseDaemonLogs(body)
    }

    override fun readNewLines(log: DaemonLog): List<DaemonLogLine> {
        val current = fetchTailLines(log.pid)
        val previous = lastTails[log.pid].orEmpty()
        lastTails[log.pid] = current
        return GoCoreLogDelta.newLinesSince(previous, current).map { line ->
            DaemonLogLine(text = line, event = DaemonLogParser.parseLine(line))
        }
    }

    override fun tailFor(log: DaemonLog): List<String> = fetchTailLines(log.pid)

    private fun fetchTailLines(pid: Long): List<String> =
        runCatching {
            val body = fetch(socketPath, "/v1/daemon-logs/$pid/tail")
            GoCoreSnapshotParser.parseDaemonLogTail(body)
        }.getOrElse { emptyList() }
}

internal object GoCoreLogDelta {
    /** Lines appended since [previous], accounting for a fixed-size ring tail. */
    fun newLinesSince(previous: List<String>, current: List<String>): List<String> {
        if (previous.isEmpty()) return current
        if (current.isEmpty()) return emptyList()
        val maxOverlap = minOf(previous.size, current.size)
        for (overlap in maxOverlap downTo 0) {
            if (previous.takeLast(overlap) == current.take(overlap)) {
                return current.drop(overlap)
            }
        }
        return current
    }
}
