package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.application.BuildHistoryReader
import io.github.cdsap.daemonitor.domain.model.Build

/** Port for build history persistence and queries. */
interface BuildRepository : BuildHistoryReader {
    fun save(build: Build)
    fun search(query: String, limit: Long = DEFAULT_QUERY_LIMIT): List<Build>
    fun findByDaemon(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<Build>

    companion object {
        const val DEFAULT_QUERY_LIMIT = 50L
    }
}
