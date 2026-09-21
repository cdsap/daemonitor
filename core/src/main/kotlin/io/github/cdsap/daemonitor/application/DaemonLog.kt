package io.github.cdsap.daemonitor.application

import java.nio.file.Path

/** A discovered daemon log with its PID and originating Gradle version. */
data class DaemonLog(val pid: Long, val gradleVersion: String, val path: Path)
