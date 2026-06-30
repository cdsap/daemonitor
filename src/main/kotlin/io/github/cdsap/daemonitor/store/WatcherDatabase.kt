package io.github.cdsap.daemonitor.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
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
 */
class WatcherDatabase private constructor(
    private val db: WatcherDb,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {

    override fun close() = driver.close()

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
    fun purgeOlderThan(nowMs: Long, retentionDays: Long = Defaults.DEFAULT_RETENTION_DAYS) {
        val cutoff = nowMs - retentionDays * 24 * 60 * 60 * 1000
        db.watcherQueries.purgeSamplesOlderThan(cutoff)
        db.watcherQueries.purgeBuildsOlderThan(cutoff)
    }

    companion object {
        /** Open (creating if necessary) the database at [path], applying privacy hardening. */
        fun open(
            path: Path = Defaults.DATABASE_PATH,
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
    }
}
