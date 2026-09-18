package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.domain.model.ProcessType

/** A persisted process snapshot used by history and MCP queries. */
data class ProcessSample(
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
    /** Live heap used (MB); null when unavailable — never coerced from missing to zero. */
    val heapUsedMb: Long? = null,
    val heapCommittedMb: Long? = null,
    val heapMaxMb: Long? = null,
    val heapSampledAtMs: Long? = null,
    val heapAvailable: Boolean = false,
)
