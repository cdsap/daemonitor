package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.persistence.BuildRepository

/** Application service for querying retained build history and project filters. */
class HistoryService(
    private val builds: BuildRepository,
) {
    fun history(): List<Build> = builds.recent()

    fun projects(): List<String> = builds.distinctProjects()
}
