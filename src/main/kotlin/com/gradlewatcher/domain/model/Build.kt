package com.gradlewatcher.domain.model

/** Final state of a build invocation (U5). */
enum class FinalStatus {
    SUCCESS,
    FAILED,
    /** Bracket closed with no outcome line in the daemon log (outcome relayed only to the client). */
    COMPLETED_NO_OUTCOME,
    /** Daemon PID disappeared mid-build. */
    INTERRUPTED,
}

/**
 * One confirmed build invocation, reconstructed by correlating daemon-log events with poll
 * samples (U5). A daemon serves many of these over its PID lifetime (KTD-1). `commandLine` and
 * `logSnippet` are already redacted (KTD-7). Peak/avg fields are null for sub-poll builds (KTD-2).
 */
data class Build(
    val buildId: String,
    val daemonPid: Long,
    /** Daemon-context-derived identity (uid), stable across PID churn — NOT the project path (HTD). */
    val daemonIdentity: String?,
    val commandLine: String?,
    val workingDirectory: String?,
    val projectPath: String?,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val durationSeconds: Double?,
    val peakMemoryMb: Long?,
    val avgMemoryMb: Long?,
    val peakCpuPercent: Double?,
    val inferredSource: Source,
    val finalStatus: FinalStatus,
    val logSnippet: String?,
)
