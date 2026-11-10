package io.github.cdsap.daemonitor.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.cdsap.daemonitor.application.BuildWriter
import io.github.cdsap.daemonitor.application.BuildHistoryEntry
import io.github.cdsap.daemonitor.application.HistoryEntry
import io.github.cdsap.daemonitor.application.ObservationProcessSummary
import io.github.cdsap.daemonitor.application.ObservationSessionHistoryEntry
import io.github.cdsap.daemonitor.application.ProcessSampleWriter
import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildSample
import io.github.cdsap.daemonitor.domain.BuildSampleProvider
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSample
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.persistence.HistoryRepository
import io.github.cdsap.daemonitor.persistence.CollectionCapability
import io.github.cdsap.daemonitor.persistence.MetricDefinition
import io.github.cdsap.daemonitor.persistence.MetricSample
import io.github.cdsap.daemonitor.persistence.NormalizedMetricSample
import io.github.cdsap.daemonitor.persistence.ProcessEvent
import io.github.cdsap.daemonitor.persistence.ProcessInstance
import io.github.cdsap.daemonitor.persistence.ResourceScope
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
import java.util.Properties
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
    private val driver: JdbcSqliteDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable,
    BuildWriter,
    ProcessSampleWriter,
    BuildRepository,
    ProcessSampleRepository,
    RetentionRepository,
    HistoryRepository,
    BuildSampleProvider {

    override fun close() = driver.close()

    override fun save(sample: GradleProcess, timestampMs: Long) = insertSample(sample, timestampMs)

    override fun save(build: Build) = insertBuild(build)

    fun insertSample(sample: GradleProcess, timestampMs: Long) {
        val live = sample.liveHeap
        val heapAvailable = live?.available == true
        db.transaction {
            db.watcherQueries.upsertProcessInstance(
                instance_id = processInstanceId(sample),
                pid = sample.pid,
                process_type = sample.type.name,
                config_json = null,
                first_observed_at = sample.startTimeMs,
                last_observed_at = timestampMs,
            )
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
            heap_used_mb = live?.usedMb.takeIf { heapAvailable },
            heap_committed_mb = live?.committedMb.takeIf { heapAvailable },
            heap_max_mb = live?.maxMb.takeIf { heapAvailable },
            heap_sampled_at_ms = live?.sampledAtMs,
            heap_available = if (live == null) null else if (heapAvailable) 1L else 0L,
            virtual_memory_mb = sample.virtualMemoryMb,
            swap_memory_mb = sample.swapMemoryMb,
            thread_count = sample.threadCount,
            read_bytes = sample.readBytes,
            write_bytes = sample.writeBytes,
            read_operations = sample.readOperations,
            write_operations = sample.writeOperations,
            minor_page_faults = sample.minorPageFaults,
            major_page_faults = sample.majorPageFaults,
            voluntary_context_switches = sample.voluntaryContextSwitches,
            involuntary_context_switches = sample.involuntaryContextSwitches,
            open_file_descriptors = sample.openFileDescriptors,
            metaspace_used_mb = sample.metaspaceUsedMb,
            metaspace_committed_mb = sample.metaspaceCommittedMb,
            young_gc_count = sample.youngGcCount,
            young_gc_time_ms = sample.youngGcTimeMs,
            old_gc_count = sample.oldGcCount,
            old_gc_time_ms = sample.oldGcTimeMs,
            java_version = sample.javaVersion,
            java_vendor = sample.javaVendor,
            active_processor_count = sample.activeProcessorCount,
            status = sample.status,
            )
        }
    }

    /** Stable identity for a process lifetime; a reused PID receives a new instance. */
    fun processInstanceId(sample: GradleProcess): String = processInstanceId(sample.pid, sample.startTimeMs)

    fun processInstanceId(pid: Long, startTimeMs: Long): String = "$pid:$startTimeMs"

    fun upsertProcessInstance(instance: ProcessInstance) {
        db.watcherQueries.upsertProcessInstance(
            instance.instanceId, instance.pid, instance.processType.name, instance.configJson,
            instance.firstObservedAtMs, instance.lastObservedAtMs,
        )
    }

    fun processInstancesForPid(pid: Long): List<ProcessInstance> =
        db.watcherQueries.processInstancesByPid(pid).executeAsList().map {
            ProcessInstance(
                instanceId = it.instance_id,
                pid = it.pid,
                processType = runCatching { ProcessType.valueOf(it.process_type) }.getOrDefault(ProcessType.UNKNOWN_GRADLE_WORKER),
                configJson = it.config_json,
                firstObservedAtMs = it.first_observed_at,
                lastObservedAtMs = it.last_observed_at,
            )
        }

    fun insertMetricDefinition(definition: MetricDefinition) {
        db.watcherQueries.insertMetricDefinition(
            definition.metricId, definition.name, definition.unit, definition.kind,
            definition.temporality, definition.source, definition.platform,
        )
    }

    fun insertMetricSample(sample: MetricSample) {
        db.watcherQueries.insertMetricSample(
            sample.timestampMs, sample.instanceId, sample.scopeId, sample.metricId,
            sample.intValue, sample.realValue, sample.source, sample.sampledAtMs,
        )
    }

    fun insertProcessEvent(event: ProcessEvent) {
        db.watcherQueries.insertProcessEvent(
            event.instanceId, event.timestampMs, event.eventType, event.metadataJson, event.source,
        )
    }

    fun upsertResourceScope(scope: ResourceScope) {
        db.watcherQueries.upsertResourceScope(
            scope.scopeId, scope.scopeType, scope.identity, scope.metadataJson,
            scope.firstObservedAtMs, scope.lastObservedAtMs,
        )
    }

    fun upsertCollectionCapability(capability: CollectionCapability) {
        db.watcherQueries.upsertCollectionCapability(
            capability.capabilityId, capability.name, capability.source,
            if (capability.supported) 1L else 0L, if (capability.available) 1L else 0L,
            capability.detailsJson, capability.updatedAtMs,
        )
    }

    fun metricSamples(
        metricId: String,
        fromMs: Long = Long.MIN_VALUE,
        toMs: Long = Long.MAX_VALUE,
        instanceId: String? = null,
        limit: Long = DEFAULT_QUERY_LIMIT,
    ): List<NormalizedMetricSample> {
        val max = limit.coerceQueryLimit()
        val normalized = db.watcherQueries.normalizedMetricSamples(
            metricId, fromMs, toMs, max,
        ).executeAsList().map {
            NormalizedMetricSample(it.timestamp, it.instance_id, it.metric_id, it.int_value,
                it.real_value, it.source, it.sampled_at)
        }
        val legacy = when (metricId) {
            LEGACY_RSS_METRIC -> db.watcherQueries.legacyMetricSamples(fromMs, toMs, max)
                .executeAsList().map { NormalizedMetricSample(it.timestamp, "legacy-pid:${it.pid}", metricId, it.rss_memory_mb, null, "legacy", it.timestamp) }
            LEGACY_CPU_METRIC -> db.watcherQueries.legacyMetricSamples(fromMs, toMs, max)
                .executeAsList().mapNotNull { it.cpu_percent?.let { value -> NormalizedMetricSample(it.timestamp, "legacy-pid:${it.pid}", metricId, null, value, "legacy", it.timestamp) } }
            else -> emptyList()
        }
        return (normalized + legacy).filter { instanceId == null || it.instanceId == instanceId }
            .sortedByDescending { it.timestampMs }.take(max.toInt())
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
    override fun samplesInWindow(pid: Long, startMs: Long, endMs: Long): List<BuildSample> =
        db.watcherQueries.samplesInWindow(pid, startMs, endMs)
            .executeAsList()
            .map { BuildSample(rssMemoryMb = it.rss_memory_mb, cpuPercent = it.cpu_percent) }

    override fun samples(pid: Long, fromMs: Long, toMs: Long): List<BuildSample> =
        samplesInWindow(pid, fromMs, toMs)

    fun processSampleCount(type: ProcessType): Long =
        db.watcherQueries.countProcessSamplesByType(type.name).executeAsOne()

    fun countByType(type: ProcessType): Long = processSampleCount(type)

    internal fun freelistPageCount(): Long = pragmaLong("freelist_count")

    internal fun autoVacuumMode(): Long = pragmaLong("auto_vacuum")

    internal fun busyTimeoutMs(): Long = pragmaLong("busy_timeout")

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
            sanitizedQuery,
            limit.coerceQueryLimit(),
        ).executeAsList().map { it.toDomain() }
    }

    override fun search(query: String, limit: Long): List<Build> =
        searchBuilds(query, limit)

    override fun searchHistory(query: String, limit: Long): List<HistoryEntry> {
        val safeLimit = limit.coerceQueryLimit()
        val needle = query.trim()
        val builds = searchBuilds(needle, safeLimit).map(::BuildHistoryEntry)
        val sessions = (if (needle.isEmpty()) {
            db.watcherQueries.recentObservationSessions(safeLimit)
        } else {
            db.watcherQueries.searchObservationSessions(needle, needle, needle, safeLimit)
        }).executeAsList().map(::observationSession)
        return (builds + sessions).sortedByDescending { it.startTimeMs }.take(safeLimit.toInt())
    }

    private fun observationSession(row: io.github.cdsap.daemonitor.store.db.Observation_sessions): ObservationSessionHistoryEntry {
        val end = row.closed_at_ms ?: Long.MAX_VALUE
        val totals = db.watcherQueries.observationBuildTotals(row.opened_at_ms, end).executeAsOne()
        val processes = db.watcherQueries.observationSessionProcesses(row.name).executeAsList().map {
            ObservationProcessSummary(
                hostId = it.host_id,
                pid = it.pid,
                startTimeMs = it.start_time_ms,
                sampleCount = it.sample_count,
                firstRssMb = it.first_rss_mb,
                peakRssMb = it.peak_rss_mb,
                lastRssMb = it.last_rss_mb,
                deltaRssMb = it.delta_rss_mb,
                association = it.association,
            )
        }
        return ObservationSessionHistoryEntry(
            name = row.name,
            hostId = row.host_id,
            association = row.association,
            startTimeMs = row.opened_at_ms,
            endTimeMs = row.closed_at_ms,
            processes = processes,
            buildCount = totals.build_count,
            successCount = totals.success_count,
            failureCount = totals.failure_count,
        )
    }

    fun recentProcessSamples(limit: Long = DEFAULT_QUERY_LIMIT): List<ProcessSample> =
        db.watcherQueries.recentProcessSamples(limit.coerceQueryLimit()).executeAsList()
            .map { it.toDomain() }

    override fun recentSamples(limit: Long): List<ProcessSample> =
        recentProcessSamples(limit)

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
        val cutoff = RetentionPolicy.DEFAULT.cutoffEpochMs(nowMs, retentionDays)
        db.watcherQueries.purgeSamplesOlderThan(cutoff)
        db.watcherQueries.purgeBuildsOlderThan(cutoff)
        db.watcherQueries.purgeMetricSamplesOlderThan(cutoff)
        db.watcherQueries.purgeProcessEventsOlderThan(cutoff)
        db.watcherQueries.purgeProcessInstancesOlderThan(cutoff)
        compactAfterPurge()
    }

    /**
     * Reclaim pages released by retention without allowing one purge to monopolize SQLite.
     *
     * Legacy databases (`auto_vacuum=NONE`) get a one-time full `VACUUM` only when the freelist
     * is non-empty, which both shrinks the file and migrates to incremental auto-vacuum.
     * New databases and later purges reclaim at most [MAX_INCREMENTAL_VACUUM_PAGES] per call so
     * a large freelist cannot hold an exclusive lock indefinitely.
     */
    private fun compactAfterPurge() {
        runCatching {
            withConnection { connection ->
                // Flush WAL (if any) so VACUUM can take an exclusive lock on Windows.
                connection.createStatement().use { statement ->
                    runCatching { statement.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                }

                val freelist = connection.pragmaLong("freelist_count")
                if (freelist <= 0L) return@withConnection

                val autoVacuum = connection.pragmaLong("auto_vacuum")
                connection.createStatement().use { statement ->
                    if (autoVacuum == AUTO_VACUUM_NONE) {
                        // Setting the mode without VACUUM must not happen alone: the pragma would
                        // report INCREMENTAL while the file still used NONE, and incremental_vacuum
                        // would no-op. Use JDBC statements — SqlDriver.execute does not reliably
                        // apply auto_vacuum changes on an existing schema.
                        statement.execute("PRAGMA auto_vacuum = INCREMENTAL")
                        statement.execute("VACUUM")
                    } else {
                        statement.execute("PRAGMA incremental_vacuum($MAX_INCREMENTAL_VACUUM_PAGES)")
                    }
                }
            }
        }
    }

    /**
     * File-backed [JdbcSqliteDriver] uses a ThreadLocal connection manager where [JdbcSqliteDriver.close]
     * is a no-op; raw [JdbcSqliteDriver.getConnection] must be paired with [JdbcSqliteDriver.closeConnection]
     * or Windows keeps the DB file locked (JUnit @TempDir / delete failures).
     */
    private inline fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        val connection = driver.getConnection()
        try {
            return block(connection)
        } finally {
            driver.closeConnection(connection)
        }
    }

    private fun java.sql.Connection.pragmaLong(name: String): Long =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { resultSet ->
                if (resultSet.next()) resultSet.getLong(1) else 0L
            }
        }

    private fun pragmaLong(name: String): Long = withConnection { it.pragmaLong(name) }

    companion object {
        /** Open (creating if necessary) the database at [path], applying privacy hardening. */
        fun open(
            path: Path = AppDirectories.system.databasePath,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): WatcherDatabase {
            val isNew = !path.exists()
            Files.createDirectories(path.parent)

            // Pass busy_timeout via JDBC properties (not getConnection()+PRAGMA): borrowing a
            // raw connection during open can leave the SQLite file locked on Windows after close,
            // which breaks JUnit @TempDir cleanup and Files.delete in tests.
            val driver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${path.absolutePathString()}",
                properties = Properties().apply {
                    setProperty("busy_timeout", BUSY_TIMEOUT_MS.toString())
                },
            )
            if (isNew) {
                driver.execute(null, "PRAGMA auto_vacuum = INCREMENTAL", 0)
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
                "ALTER TABLE process_samples ADD COLUMN heap_used_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN heap_committed_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN heap_max_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN heap_sampled_at_ms INTEGER",
                "ALTER TABLE process_samples ADD COLUMN heap_available INTEGER",
                "ALTER TABLE process_samples ADD COLUMN virtual_memory_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN swap_memory_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN thread_count INTEGER",
                "ALTER TABLE process_samples ADD COLUMN read_bytes INTEGER",
                "ALTER TABLE process_samples ADD COLUMN write_bytes INTEGER",
                "ALTER TABLE process_samples ADD COLUMN read_operations INTEGER",
                "ALTER TABLE process_samples ADD COLUMN write_operations INTEGER",
                "ALTER TABLE process_samples ADD COLUMN minor_page_faults INTEGER",
                "ALTER TABLE process_samples ADD COLUMN major_page_faults INTEGER",
                "ALTER TABLE process_samples ADD COLUMN voluntary_context_switches INTEGER",
                "ALTER TABLE process_samples ADD COLUMN involuntary_context_switches INTEGER",
                "ALTER TABLE process_samples ADD COLUMN open_file_descriptors INTEGER",
                "ALTER TABLE process_samples ADD COLUMN metaspace_used_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN metaspace_committed_mb INTEGER",
                "ALTER TABLE process_samples ADD COLUMN young_gc_count INTEGER",
                "ALTER TABLE process_samples ADD COLUMN young_gc_time_ms INTEGER",
                "ALTER TABLE process_samples ADD COLUMN old_gc_count INTEGER",
                "ALTER TABLE process_samples ADD COLUMN old_gc_time_ms INTEGER",
                "ALTER TABLE process_samples ADD COLUMN java_version TEXT",
                "ALTER TABLE process_samples ADD COLUMN java_vendor TEXT",
                "ALTER TABLE process_samples ADD COLUMN active_processor_count INTEGER",
                "CREATE TABLE IF NOT EXISTS process_instances (instance_id TEXT PRIMARY KEY, pid INTEGER NOT NULL, process_type TEXT NOT NULL, config_json TEXT, first_observed_at INTEGER NOT NULL, last_observed_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS process_instances_pid ON process_instances(pid)",
                "CREATE TABLE IF NOT EXISTS metric_definitions (metric_id TEXT PRIMARY KEY, name TEXT NOT NULL, unit TEXT, kind TEXT NOT NULL, temporality TEXT NOT NULL, source TEXT NOT NULL, platform TEXT, UNIQUE(name, source, platform))",
                "CREATE TABLE IF NOT EXISTS resource_scopes (scope_id TEXT PRIMARY KEY, scope_type TEXT NOT NULL, identity TEXT NOT NULL, metadata_json TEXT, first_observed_at INTEGER NOT NULL, last_observed_at INTEGER NOT NULL, UNIQUE(scope_type, identity))",
                "CREATE TABLE IF NOT EXISTS metric_samples (sample_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, instance_id TEXT NOT NULL REFERENCES process_instances(instance_id), scope_id TEXT REFERENCES resource_scopes(scope_id), metric_id TEXT NOT NULL REFERENCES metric_definitions(metric_id), int_value INTEGER, real_value REAL, source TEXT NOT NULL, sampled_at INTEGER NOT NULL, CHECK ((int_value IS NOT NULL AND real_value IS NULL) OR (int_value IS NULL AND real_value IS NOT NULL)))",
                "CREATE INDEX IF NOT EXISTS metric_samples_instance_metric_ts ON metric_samples(instance_id, metric_id, timestamp)",
                "CREATE INDEX IF NOT EXISTS metric_samples_metric_ts ON metric_samples(metric_id, timestamp)",
                "CREATE TABLE IF NOT EXISTS process_events (event_id INTEGER PRIMARY KEY AUTOINCREMENT, instance_id TEXT NOT NULL REFERENCES process_instances(instance_id), timestamp INTEGER NOT NULL, event_type TEXT NOT NULL, metadata_json TEXT, source TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS process_events_instance_ts ON process_events(instance_id, timestamp)",
                "CREATE TABLE IF NOT EXISTS collection_capabilities (capability_id TEXT PRIMARY KEY, name TEXT NOT NULL, source TEXT NOT NULL, supported INTEGER NOT NULL, available INTEGER NOT NULL, details_json TEXT, updated_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS observation_sessions (name TEXT PRIMARY KEY NOT NULL, host_id TEXT NOT NULL, association TEXT NOT NULL, opened_at_ms INTEGER NOT NULL, closed_at_ms INTEGER, response_json TEXT)",
                "CREATE TABLE IF NOT EXISTS observation_session_processes (session_name TEXT NOT NULL, host_id TEXT NOT NULL, pid INTEGER NOT NULL, start_time_ms INTEGER NOT NULL, PRIMARY KEY(session_name, host_id, pid, start_time_ms), FOREIGN KEY(session_name) REFERENCES observation_sessions(name))",
                "CREATE TABLE IF NOT EXISTS observation_session_process_summaries (session_name TEXT NOT NULL, host_id TEXT NOT NULL, pid INTEGER NOT NULL, start_time_ms INTEGER NOT NULL, sample_count INTEGER NOT NULL, first_rss_mb INTEGER NOT NULL, peak_rss_mb INTEGER NOT NULL, last_rss_mb INTEGER NOT NULL, delta_rss_mb INTEGER NOT NULL, association TEXT NOT NULL, PRIMARY KEY(session_name, host_id, pid, start_time_ms), FOREIGN KEY(session_name) REFERENCES observation_sessions(name))",
                "CREATE INDEX IF NOT EXISTS observation_sessions_opened ON observation_sessions(opened_at_ms)",
            ).forEach { sql -> runCatching { driver.execute(null, sql, 0) } }
        }

        private const val DEFAULT_QUERY_LIMIT = 50L
        private const val MAX_QUERY_LIMIT = 200L
        private const val AUTO_VACUUM_NONE = 0L
        private const val MAX_INCREMENTAL_VACUUM_PAGES = 100_000
        private const val BUSY_TIMEOUT_MS = 5_000
        const val LEGACY_RSS_METRIC = "process.rss_memory_mb"
        const val LEGACY_CPU_METRIC = "process.cpu_percent"

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

        private fun Process_samples.toDomain(): ProcessSample {
            val available = heap_available == 1L
            return ProcessSample(
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
                heapUsedMb = heap_used_mb.takeIf { available },
                heapCommittedMb = heap_committed_mb.takeIf { available },
                heapMaxMb = heap_max_mb.takeIf { available },
                heapSampledAtMs = heap_sampled_at_ms,
                heapAvailable = available,
                virtualMemoryMb = virtual_memory_mb,
                swapMemoryMb = swap_memory_mb,
                threadCount = thread_count,
                readBytes = read_bytes,
                writeBytes = write_bytes,
                readOperations = read_operations,
                writeOperations = write_operations,
                minorPageFaults = minor_page_faults,
                majorPageFaults = major_page_faults,
                voluntaryContextSwitches = voluntary_context_switches,
                involuntaryContextSwitches = involuntary_context_switches,
                openFileDescriptors = open_file_descriptors,
                metaspaceUsedMb = metaspace_used_mb,
                metaspaceCommittedMb = metaspace_committed_mb,
                youngGcCount = young_gc_count,
                youngGcTimeMs = young_gc_time_ms,
                oldGcCount = old_gc_count,
                oldGcTimeMs = old_gc_time_ms,
                javaVersion = java_version,
                javaVendor = java_vendor,
                activeProcessorCount = active_processor_count,
            )
        }
    }
}
