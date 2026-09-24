package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.domain.BuildSample
import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Port for process-sample persistence and queries. */
interface ProcessSampleRepository {
    fun save(sample: GradleProcess, timestampMs: Long)

    /**
     * RSS + CPU samples for a PID within `[fromMs, toMs]`.
     *
     * Returns domain [BuildSample] values; production adapters typically share the same
     * backing query as [io.github.cdsap.daemonitor.domain.BuildSampleProvider].
     */
    fun samples(pid: Long, fromMs: Long, toMs: Long): List<BuildSample>

    fun recentSamples(limit: Long = BuildRepository.DEFAULT_QUERY_LIMIT): List<ProcessSample>
    fun findByPid(pid: Long, limit: Long = BuildRepository.DEFAULT_QUERY_LIMIT): List<ProcessSample>
}
