package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build

/** Port for querying retained build history and project filters. */
interface BuildHistoryReader {
    fun recent(): List<Build>
    fun distinctProjects(): List<String>
}
