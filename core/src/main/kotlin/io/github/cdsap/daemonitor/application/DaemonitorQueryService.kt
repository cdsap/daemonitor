package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Application-facing read API used by MCP and other query consumers. */
interface DaemonitorQueryService {
    fun searchHistory(query: String, limit: Int): List<HistoryEntry>

    fun buildsForProcess(process: String, limit: Int): ProcessBuildResult

    fun currentProcesses(): List<GradleProcess>

    fun bazelBuildDetails(buildIds: List<String>): Map<String, BazelBuildDetailsRecord> = emptyMap()
}

fun interface BazelBuildDetailsQuery {
    fun bazelBuildDetailsByIds(buildIds: List<String>): Map<String, BazelBuildDetailsRecord>
}

data class BazelBuildDetailsRecord(
    val buildId: String,
    val schemaVersion: Long,
    val bazelVersion: String?,
    val sourceFormat: String,
    val collectionState: String,
    val updatedAtMs: Long,
    val metricsJson: String,
)

data class ProcessBuildResult(
    val process: String,
    val matchedBuilds: List<Build>,
    val matchedProcessSamples: List<ProcessSampleView>,
)
