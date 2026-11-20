package io.github.cdsap.daemonitor.application

/** Application port for reading retained process samples. */
interface ProcessSampleQuery {
    fun recentSamples(limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSampleView>
    fun findByPid(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSampleView>

    companion object {
        const val DEFAULT_QUERY_LIMIT = 50L
    }
}
