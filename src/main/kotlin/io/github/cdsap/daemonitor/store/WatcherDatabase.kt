package io.github.cdsap.daemonitor.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.cdsap.daemonitor.application.BuildRepository as ApplicationBuildRepository
import io.github.cdsap.daemonitor.application.ProcessSampleRepository as ApplicationProcessSampleRepository
import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.BuildRepository as PersistenceBuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSample
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository as PersistenceProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.store.db.Process_samples
import io.github.cdsap.daemonitor.store.db.WatcherDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * Local SQLite persistence (U4 / KTD-5). On first open the DB file is created with owner-only
 * permissions (0600) and excluded from Time Machine, and stale rows are purged by retention
 * window. Reads are exposed as `Flow`s that drive the UI (KTD-5).
 *
 * Redaction invariant (KTD-7): callers pass only pre-redacted command lines / log snippets;
 * the collector (U2), log watcher (U3), and aggregator (U5) all redact upstream.
 *
 * Implements repository ports so application code can depend on interfaces rather than this
 * concrete SQLite type.
 */
class WatcherDatabase private constructor(
    private val db: WatcherDb,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable,
    ApplicationBuildRepository,
    ApplicationProcessSampleRepository,
    PersistenceBuildRepository,
    PersistenceProcessSampleRepository,
    RetentionRepository {

    override fun close() = driver.close()

    override fun save(sample: GradleProcess, timestampMs: Long) = insertSample(sample, timestampMs)

    override fun save(build: Build) = insertBuild(build)

    fun insertSample(sample: GradleProcess, timestampMs: Long) {
        db.watcherQueries.insertSample(
            timestamp = timestampMs,
            pid = sample.pid,
            parent_pid = sample.parentPid,
            process_type = sample.type.name,
            command_line = sample.commandLine,
            working_directory = sample.workingDirectory,
            project_path = sample.projectPath,
            cpu_percent = sample.cpuPercent,
            rss_memory_mb = sample.rssMemoryMb,
            max_heap_mb = sample.maxHeapMb,
            status = sample.status,
        )
    }

    fun insertBuild(build: Build) {
        db.watcherQueries.insertBuild(
            build_id = build.buildId,
            daemon_pid = build.daemonPid,
            daemon_identity = build.daemonIdentity,
            command_line = build.commandLine,
            working_directory = build.workingDirectory,
            project_path = build.projectPath,
            start_time = build.startTimeMs,
            end_time = build.endTimeMs,
            duration_seconds = build.durationSeconds,
            peak_memory_mb = build.peakMemoryMb,
            avg_memory_mb = build.avgMemoryMb,
            peak_cpu_percent = build.peakCpuPercent,
            inferred_source = build.inferredSource.name,
            final_status = build.finalStatus.name,
            log_snippet = build.logSnippet,
            agent = build.agent,
            agent_provider = build.agentProvider,
        )
    }

    /** RSS + CPU samples for a PID within [startMs, endMs] -- used by the aggregator (U5). */
    fun samplesInWindow(pid: Long, startMs: Long, endMs: Long): List<Pair<Long, Double?>> =
        db.watcherQueries.samplesInWindow(pid, startMs, endMs)
            .executeAsList()
            .map { it.rss_memory_mb to it.cpu_percent }

    override fun samples(pid: Long, fromMs: Long, toMs: Long): List<Pair<Long, Double?>> =
        samplesInWindow(pid, fromMs, toMs)

    fun processSampleCount(type: ProcessType): Long =
        db.watcherQueries.countProcessSamplesByType(type.name).executeAsOne()

    fun countByType(type: ProcessType): Long = processSampleCount(type)

    /** One-shot snapshot of all retained builds, newest first. */
    fun recentBuilds(): List<Build> =
        db.watcherQueries.recentBuilds().executeAsList().map { it.toDomain() }

    override fun recent(): List<Build> = recentBuilds()

    fun buildsForDaemonPid(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<Build> =
        db.watcherQueries.buildsForDaemonPid(pid, limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

    override fun findByDaemon(pid: Long, limit: Long): List<Build> =
        buildsForDaemonPid(pid, limit)

    fun searchBuilds(query: String, limit: Long = DEFAULT_QUERY_LIMIT): List<Build> {
        val sanitizedQuery = query.trim()
        if (sanitizedQuery.isEmpty()) return recentBuilds().take(limit.coerceQueryLimit().toInt())
        return db.watcherQueries.searchBuilds(
            sanitizedQuery,
            sanitizedQuery,
            sanitizedQuery,
            sanitizedQuery,
            sanitizedQuery,
            sanitizedQuery,
            sanitizedQuery,
            sanitizedQuery,
            limit.coerceQueryLimit(),
        ).executeAsList().map { it.toDomain() }
    }

    override fun search(query: String, limit: Long): List<Build> =
        searchBuilds(query, limit)

    fun recentProcessSamples(limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSample> =
        db.watcherQueries.recentProcessSamples(limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

    override fun recentSamples(limit: Long): List<ProcessSample> =
        recentProcessSamples(limit)

    override fun samplesInRange(fromMs: Long, toMs: Long): List<ProcessSample> =
        db.watcherQueries.processSamplesInRange(fromMs, toMs).executeAsList()
            .map { it.toDomain() }

    fun processSamplesForPid(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSample> =
        db.watcherQueries.processSamplesForPid(pid, limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

    override fun findByPid(pid: Long, limit: Long): List<ProcessSample> =
        processSamplesForPid(pid, limit)

    /** One-shot snapshot of distinct project paths for the History filter dropdown. */
    override fun distinctProjects(): List<String> =
        db.watcherQueries.distinctProjects().executeAsList()

    fun buildsFlow(): Flow<List<Build>> =
        db.watcherQueries.recentBuilds().asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { it.toDomain() }
        }

    fun buildsSinceFlow(startMs: Long): Flow<List<Build>> =
        db.watcherQueries.buildsSince(startMs).asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { it.toDomain() }
        }

    fun distinctProjectsFlow(): Flow<List<String>> =
        db.watcherQueries.distinctProjects().asFlow().mapToList(ioDispatcher)

    /** Delete samples and builds older than [retentionDays] before [nowMs] (KTD-5). */
    override fun purgeOlderThan(nowMs: Long, retentionDays: Long) {
        val cutoff = nowMs - retentionDays * 24 * 60 * 60 * 1000
        db.watcherQueries.purgeSamplesOlderThan(cutoff)
        db.watcherQueries.purgeBuildsOlderThan(cutoff)
    }

    companion object {
        /** Open (creating if necessary) the database at [path], applying privacy hardening. */
        fun open(
            path: Path = AppDirectories.system.databasePath,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): WatcherDatabase {
            val isNew = !path.exists()
            Files.createDirectories(path.parent)

            val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
            if (isNew) {
                WatcherDb.Schema.create(driver)
                hardenFilePrivacy(path)
            } else {
                migrateInPlace(driver)
            }
            return WatcherDatabase(WatcherDb(driver), driver, ioDispatcher)
        }

        /**
         * Add columns introduced after a DB was first created. SQLite has no ADD COLUMN IF NOT
         * EXISTS, so duplicate-column errors are ignored.
         */
        private fun migrateInPlace(driver: JdbcSqliteDriver) {
            listOf(
                "ALTER TABLE builds ADD COLUMN agent TEXT",
                "ALTER TABLE builds ADD COLUMN agent_provider TEXT",
            ).forEach { sql -> runCatching { driver.execute(null, sql, 0) } }
        }

        private const val DEFAULT_QUERY_LIMIT = 50L
        private const val MAX_QUERY_LIMIT = 200L

        private fun Long.coerceQueryLimit(): Long = coerceIn(1, MAX_QUERY_LIMIT)

        /** Owner-only file permissions + Time Machine exclusion (KTD-7/Privacy). Best-effort. */
        private fun hardenFilePrivacy(path: Path) {
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
            runCatching {
                ProcessBuilder("tmutil", "addexclusion", path.parent.absolutePathString())
                    .start().waitFor()
            }
        }

        private fun io.github.cdsap.daemonitor.store.db.Builds.toDomain(): Build = Build(
            buildId = build_id,
            daemonPid = daemon_pid,
            daemonIdentity = daemon_identity,
            commandLine = command_line,
            workingDirectory = working_directory,
            projectPath = project_path,
            startTimeMs = start_time,
            endTimeMs = end_time,
            durationSeconds = duration_seconds,
            peakMemoryMb = peak_memory_mb,
            avgMemoryMb = avg_memory_mb,
            peakCpuPercent = peak_cpu_percent,
            inferredSource = runCatching { Source.valueOf(inferred_source) }.getOrDefault(Source.UNKNOWN),
            finalStatus = runCatching { FinalStatus.valueOf(final_status) }
                .getOrDefault(FinalStatus.COMPLETED_NO_OUTCOME),
            logSnippet = log_snippet,
            agent = agent,
            agentProvider = agent_provider,
        )

        private fun Process_samples.toDomain(): ProcessSample = ProcessSample(
            timestampMs = timestamp,
            pid = pid,
            parentPid = parent_pid,
            processType = runCatching { ProcessType.valueOf(process_type) }
                .getOrDefault(ProcessType.JAVA_GRADLE_RELATED),
            commandLine = command_line,
            workingDirectory = working_directory,
            projectPath = project_path,
            cpuPercent = cpu_percent,
            rssMemoryMb = rss_memory_mb,
            maxHeapMb = max_heap_mb,
            status = status,
        )
    }
}
