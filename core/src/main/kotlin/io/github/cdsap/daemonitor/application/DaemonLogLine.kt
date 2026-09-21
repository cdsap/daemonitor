package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.BuildEvent

/** One complete, redacted daemon-log line and the build event parsed from that same line. */
data class DaemonLogLine(val text: String, val event: BuildEvent?)
