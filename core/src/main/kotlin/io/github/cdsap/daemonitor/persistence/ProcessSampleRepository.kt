package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.domain.BuildSample
import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Port for process-sample persistence and build-correlation samples. */
interface ProcessSampleRepository {
    fun save(sample: GradleProcess, timestampMs: Long)

    /**
     * RSS + CPU samples for a PID within `[fromMs, toMs]`.
     *
     * Returns domain [BuildSample] values; production adapters typically share the same
     * backing query as [io.github.cdsap.daemonitor.domain.BuildSampleProvider].
     */
    fun samples(pid: Long, fromMs: Long, toMs: Long): List<BuildSample>
}

/** Read-only port for the Visual tab's retained process timeline. */
fun interface RetainedProcessSampleRepository {
    /** Timeline fields for all retained processes within `[fromMs, toMs]`, oldest first. */
    fun samplesInRange(fromMs: Long, toMs: Long): List<RetainedProcessSample>
}
