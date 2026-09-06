package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Port for process-sample persistence and queries. */
interface ProcessSampleRepository {
    fun save(sample: GradleProcess, timestampMs: Long)

    /**
     * RSS + CPU samples for a PID within `[fromMs, toMs]` — used by the build aggregator.
     * Each pair is `(rssMemoryMb, cpuPercent)`.
     */
    fun samples(pid: Long, fromMs: Long, toMs: Long): List<Pair<Long, Double?>>

    fun samplesInRange(fromMs: Long, toMs: Long): List<ProcessSample>

    fun recentSamples(limit: Long = BuildRepository.DEFAULT_QUERY_LIMIT): List<ProcessSample>
    fun findByPid(pid: Long, limit: Long = BuildRepository.DEFAULT_QUERY_LIMIT): List<ProcessSample>
}
