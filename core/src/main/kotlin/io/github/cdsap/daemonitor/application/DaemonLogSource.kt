package io.github.cdsap.daemonitor.application

/** Port for discovering daemon logs and reading newly appended lines. */
interface DaemonLogSource {
    fun discover(): List<DaemonLog>

    fun readNewLines(log: DaemonLog): List<DaemonLogLine>

    /** Last retained redacted lines for a discovered log (live tail panel). */
    fun tailFor(log: DaemonLog): List<String>
}

/** Result of reading a selected daemon's tail, distinguishing no discovered log from a failure. */
sealed interface DaemonLogTailResult {
    data object NoLog : DaemonLogTailResult
    data class Lines(val lines: List<String>) : DaemonLogTailResult
    data class Error(val errorType: String) : DaemonLogTailResult
}
