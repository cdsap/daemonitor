package io.github.cdsap.daemonitor.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import io.github.cdsap.daemonitor.application.BuildRepository
import io.github.cdsap.daemonitor.application.ProcessSampleRepository
import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
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
 * Implements [BuildRepository] and [ProcessSampleRepository] so application polling depends on
 * ports rather than this concrete SQLite type.
 *
 * Redaction invariant (KTD-7): callers pass only pre-redacted command lines / log snippets;
 * the collector (U2), log watcher (U3), and aggregator (U5) all redact upstream.
 */
class WatcherDatabase private constructor(
    private val db: WatcherDb,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable, BuildRepository, ProcessSampleRepository {

    override fun close() = driver.close()

    override fun save(sample: GradleProcess, timestampMs: Long) = insertSample(sample, timestampMs)

    override fun save(build: Build) = insertBuild(build)

    fun insertSample(p: GradleProcess, timestampMs: Long) {
        db.watcherQueries.insertSample(
            timestamp = timestampMs,
            pid = p.pid,
            parent_pid = p.parentPid,
            process_type = p.type.name,
            command_line = p.commandLine,
            working_directory = p.workingDirectory,
            project_path = p.projectPath,
            cpu_percent = p.cpuPercent,
            rss_memory_mb = p.rssMemoryMb,
            max_heap_mb = p.maxHeapMb,
            status = p.status,
        )
    }

    fun insertBuild(b: Build) {
        db.watcherQueries.insertBuild(
            build_id = b.buildId,
            daemon_pid = b.daemonPid,
            daemon_identity = b.daemonIdentity,
            command_line = b.commandLine,
            working_directory = b.workingDirectory,
            project_path = b.projectPath,
            start_time = b.startTimeMs,
            end_time = b.endTimeMs,
            duration_seconds = b.durationSeconds,
            peak_memory_mb = b.peakMemoryMb,
            avg_memory_mb = b.avgMemoryMb,
            peak_cpu_percent = b.peakCpuPercent,
            inferred_source = b.inferredSource.name,
            final_status = b.finalStatus.name,
            log_snippet = b.logSnippet,
            agent = b.agent,
            agent_provider = b.agentProvider,
        )
    }

    /** RSS + CPU samples for a PID within [startMs, endMs] — used by the aggregator (U5). */
    fun samplesInWindow(pid: Long, startMs: Long, endMs: Long): List<Pair<Long, Double?>> =
        db.watcherQueries.samplesInWindow(pid, startMs, endMs)
            .executeAsList()
            .map { it.rss_memory_mb to it.cpu_percent }

    fun processSampleCount(type: ProcessType): Long =
        db.watcherQueries.countProcessSamplesByType(type.name).executeAsOne()

    /** One-shot snapshot of all retained builds, newest first. Drives the explicit History refresh
     *  the poll loop triggers after inserting builds (reactive `asFlow` notifications proved
     *  unreliable for live updates with the JDBC SQLite driver). */
    fun recentBuilds(): List<Build> =
        db.watcherQueries.recentBuilds().executeAsList().map { it.toDomain() }

    fun buildsForDaemonPid(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<Build> =
        db.watcherQueries.buildsForDaemonPid(pid, limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

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

    fun recentProcessSamples(limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSample> =
        db.watcherQueries.recentProcessSamples(limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

    fun processSamplesForPid(pid: Long, limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSample> =
        db.watcherQueries.processSamplesForPid(pid, limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

    /** One-shot snapshot of distinct project paths for the History filter dropdown. */
    fun distinctProjects(): List<String> =
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
    fun purgeOlderThan(nowMs: Long, retentionDays: Long = RetentionPolicy.DEFAULT.defaultDays) {
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

        /** Add columns introduced after a DB was first created. SQLite has no ADD COLUMN IF NOT
         *  EXISTS, so each ALTER is attempted and the "duplicate column" error is ignored — keeps
         *  a pre-existing local watcher.db working without a full migration framework. */
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
