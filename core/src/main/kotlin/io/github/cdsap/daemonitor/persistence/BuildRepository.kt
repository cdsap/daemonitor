package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.domain.model.Build

/** Port for build history persistence and queries. */
interface BuildRepository {
    fun save(build: Build)
    fun recent(): List<Build>
    fun search(query: String, limit: Long = DEFAULT_QUERY_LIMIT): List<Build>
    fun findByDaemon(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<Build>
    fun distinctProjects(): List<String>

    companion object {
        const val DEFAULT_QUERY_LIMIT = 50L
    }
}
