package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildEvent

/** Domain port for correlating daemon-log events into builds. */
interface BuildCorrelator {
    fun onEvents(daemonPid: Long, events: List<BuildEvent>): List<Build>

    fun onLogLine(daemonPid: Long, line: String, event: BuildEvent?): List<Build>

    fun onDaemonGone(daemonPid: Long): Build?
}
