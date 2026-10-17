package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.BuildEvent

/** One complete, redacted daemon-log line returned by a [DaemonLogSource]. */
data class DaemonLogLine(val text: String)

/** A redacted line after application-level parsing, immediately before build correlation. */
data class ParsedDaemonLogLine(val text: String, val event: BuildEvent?)
