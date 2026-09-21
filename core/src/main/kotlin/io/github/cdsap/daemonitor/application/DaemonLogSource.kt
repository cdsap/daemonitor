package io.github.cdsap.daemonitor.application

/** Port for discovering daemon logs and reading newly appended lines. */
interface DaemonLogSource {
    fun discover(): List<DaemonLog>

    fun readNewLines(log: DaemonLog): List<DaemonLogLine>

    /** Last retained redacted lines for a discovered log (live tail panel). */
    fun tailFor(log: DaemonLog): List<String>
}
