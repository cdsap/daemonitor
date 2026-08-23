package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.store.WatcherDatabase

/** Application service for querying retained build history and project filters. */
class HistoryService(
    private val database: WatcherDatabase,
) {
    fun history(): List<Build> = database.recentBuilds()

    fun projects(): List<String> = database.distinctProjects()
}
