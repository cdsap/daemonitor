package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Port for persisting process samples collected during polling. */
interface ProcessSampleRepository {
    fun save(sample: GradleProcess, timestampMs: Long)
}
