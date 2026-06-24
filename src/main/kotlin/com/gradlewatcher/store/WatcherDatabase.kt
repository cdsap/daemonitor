package com.gradlewatcher.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gradlewatcher.Defaults
import com.gradlewatcher.domain.model.Build
import com.gradlewatcher.domain.model.FinalStatus
import com.gradlewatcher.domain.model.GradleProcess
import com.gradlewatcher.domain.model.Source
import com.gradlewatcher.store.db.WatcherDb
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
    private val ioDispatcher: CoroutineDispatcher,
) {

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
        )
    }

    /** RSS + CPU samples for a PID within [startMs, endMs] — used by the aggregator (U5). */
    fun samplesInWindow(pid: Long, startMs: Long, endMs: Long): List<Pair<Long, Double?>> =
        db.watcherQueries.samplesInWindow(pid, startMs, endMs)
            .executeAsList()
            .map { it.rss_memory_mb to it.cpu_percent }

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

    fun purgeOlderThan(nowMs: Long) {
        val cutoff = nowMs - Defaults.RETENTION_DAYS * 24 * 60 * 60 * 1000
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
            }
            return WatcherDatabase(WatcherDb(driver), ioDispatcher)
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

        private fun com.gradlewatcher.store.db.Builds.toDomain(): Build = Build(
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
        )
    }
}
