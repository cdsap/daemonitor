package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.HistoryRepository

/**
 * Query service backed by persistence reads and a [ProcessSource].
 * Keeps MCP free of SQLite and OSHI collection details.
 */
class DefaultDaemonitorQueryService(
    private val builds: BuildRepository,
    private val samples: ProcessSampleQuery,
    private val processSource: ProcessSource,
    private val history: HistoryRepository = (builds as? HistoryRepository) ?: object : HistoryRepository {
        override fun searchHistory(query: String, limit: Long): List<HistoryEntry> =
            builds.search(query, limit).map(::BuildHistoryEntry)
    },
    private val bazelDetails: BazelBuildDetailsQuery? = builds as? BazelBuildDetailsQuery,
) : DaemonitorQueryService {
    override fun searchHistory(query: String, limit: Int): List<HistoryEntry> =
        history.searchHistory(query, limit.coerceQueryLimit())

    override fun bazelBuildDetails(buildIds: List<String>): Map<String, BazelBuildDetailsRecord> {
        val ids = buildIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return bazelDetails?.bazelBuildDetailsByIds(ids).orEmpty()
    }

    override fun buildsForProcess(process: String, limit: Int): ProcessBuildResult {
        val safeLimit = limit.coerceQueryLimit()
        val pid = process.toLongOrNull()
        val matchedBuilds = if (pid != null) {
            builds.findByDaemon(pid, safeLimit)
        } else {
            builds.search(process, safeLimit)
        }
        val matchedSamples = if (pid != null) {
            samples.findByPid(pid, safeLimit)
        } else {
            samples.recentSamples(TEXT_MATCH_SAMPLE_SCAN_LIMIT).filter { sample ->
                sample.commandLine.contains(process, ignoreCase = true) ||
                    sample.workingDirectory?.contains(process, ignoreCase = true) == true ||
                    sample.projectPath?.contains(process, ignoreCase = true) == true ||
                    sample.processType.name.contains(process, ignoreCase = true)
            }.take(safeLimit.toInt())
        }
        return ProcessBuildResult(
            process = process,
            matchedBuilds = matchedBuilds,
            matchedProcessSamples = matchedSamples,
        )
    }

    override fun currentProcesses(): List<GradleProcess> = processSource.currentProcesses()

    private fun Int.coerceQueryLimit(): Long = toLong().coerceIn(1, MAX_LIMIT)

    private companion object {
        const val MAX_LIMIT = 200L
        const val TEXT_MATCH_SAMPLE_SCAN_LIMIT = 200L
    }
}
