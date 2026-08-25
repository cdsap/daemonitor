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
)
