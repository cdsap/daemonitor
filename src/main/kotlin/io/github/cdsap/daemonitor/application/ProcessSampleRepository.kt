package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.persistence.ProcessSample

/** Port for persisting process samples collected during polling. */
interface ProcessSampleRepository {
    fun save(sample: GradleProcess, timestampMs: Long)

    fun samplesInRange(fromMs: Long, toMs: Long): List<ProcessSample>
}
