package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.store.WatcherDatabase

/**
 * Query service backed by persistence reads and a [ProcessSource].
 * Keeps MCP free of SQLite and OSHI collection details.
 */
class DefaultDaemonitorQueryService(
    private val database: WatcherDatabase,
    private val processSource: ProcessSource,
) : DaemonitorQueryService {
    override fun searchHistory(query: String, limit: Int): List<Build> =
        database.searchBuilds(query, limit.coerceQueryLimit())

    override fun buildsForProcess(process: String, limit: Int): ProcessBuildResult {
        val safeLimit = limit.coerceQueryLimit()
        val pid = process.toLongOrNull()
        val builds = if (pid != null) {
            database.buildsForDaemonPid(pid, safeLimit)
        } else {
            database.searchBuilds(process, safeLimit)
        }
        val samples = if (pid != null) {
            database.processSamplesForPid(pid, safeLimit)
        } else {
            database.recentProcessSamples(TEXT_MATCH_SAMPLE_SCAN_LIMIT).filter { sample ->
                sample.commandLine.contains(process, ignoreCase = true) ||
                    sample.workingDirectory?.contains(process, ignoreCase = true) == true ||
                    sample.projectPath?.contains(process, ignoreCase = true) == true ||
                    sample.processType.name.contains(process, ignoreCase = true)
            }.take(safeLimit.toInt())
        }
        return ProcessBuildResult(
            process = process,
            matchedBuilds = builds,
            matchedProcessSamples = samples,
        )
    }

    override fun currentProcesses(): List<GradleProcess> = processSource.currentProcesses()

    private fun Int.coerceQueryLimit(): Long = toLong().coerceIn(1, MAX_LIMIT)

    private companion object {
        const val MAX_LIMIT = 200L
        const val TEXT_MATCH_SAMPLE_SCAN_LIMIT = 200L
    }
}
