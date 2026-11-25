package io.github.cdsap.daemonitor.application

import java.nio.file.Path

/** A discovered build-tool log with its PID, optional Gradle version, path, and tool/source metadata. */
data class DaemonLog(
    val pid: Long,
    val gradleVersion: String,
    val path: Path,
    val tool: String? = null,
    val source: String? = null,
)
