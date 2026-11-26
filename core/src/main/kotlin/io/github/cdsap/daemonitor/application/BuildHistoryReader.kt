package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build

/** Port for querying retained build history and project filters. */
interface BuildHistoryReader : HistoryReader {
    override fun recent(): List<Build>
    override fun distinctProjects(): List<String>
}
