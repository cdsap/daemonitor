package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.BuildHistoryReader
import io.github.cdsap.daemonitor.domain.model.Build

/** Application service for querying retained build history and project filters. */
class HistoryService(
    private val builds: BuildHistoryReader,
) {
    fun history(): List<Build> = builds.recent()

    fun projects(): List<String> = builds.distinctProjects()
}
