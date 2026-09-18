package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.persistence.ProcessSample

/** Application-facing read API used by MCP and other query consumers. */
interface DaemonitorQueryService {
    fun searchHistory(query: String, limit: Int): List<Build>

    fun buildsForProcess(process: String, limit: Int): ProcessBuildResult

    fun currentProcesses(): List<GradleProcess>
}

data class ProcessBuildResult(
    val process: String,
    val matchedBuilds: List<Build>,
    val matchedProcessSamples: List<ProcessSample>,
)
