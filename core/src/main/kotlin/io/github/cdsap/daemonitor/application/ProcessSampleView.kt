package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.ProcessType

/** Application-owned read model for a retained process snapshot. */
data class ProcessSampleView(
    val timestampMs: Long,
    val pid: Long,
    val parentPid: Long,
    val processType: ProcessType,
    val commandLine: String,
    val workingDirectory: String?,
    val projectPath: String?,
    val cpuPercent: Double?,
    val rssMemoryMb: Long,
    val maxHeapMb: Long?,
    val status: String,
    val heapUsedMb: Long? = null,
    val heapCommittedMb: Long? = null,
    val heapMaxMb: Long? = null,
    val heapSampledAtMs: Long? = null,
    val heapAvailable: Boolean = false,
)
