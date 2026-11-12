package io.github.cdsap.daemonitor.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.cdsap.daemonitor.domain.BuildSample
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.persistence.HistoryRepository
import io.github.cdsap.daemonitor.persistence.MetricDefinition
import io.github.cdsap.daemonitor.persistence.MetricSample
import io.github.cdsap.daemonitor.persistence.ProcessEvent
import io.github.cdsap.daemonitor.store.db.WatcherDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatcherDatabaseTest {

    private fun build(id: String, startMs: Long, project: String = "/p") = Build(
        buildId = id,
        daemonPid = 1,
        daemonIdentity = "uid-1",
        commandLine = "gradlew build",
        workingDirectory = project,
        projectPath = project,
        startTimeMs = startMs,
        endTimeMs = startMs + 3000,
        durationSeconds = 3.0,
        peakMemoryMb = 700,
        avgMemoryMb = 600,
        peakCpuPercent = 50.0,
        inferredSource = Source.TERMINAL,
        finalStatus = FinalStatus.SUCCESS,
        logSnippet = "BUILD SUCCESSFUL in 3s",
        agent = "Claude Code",
        agentProvider = "Anthropic",
    )

    @Test
    fun `build round-trips through the database`(@TempDirArg tmp: Path) = runTest {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.save(build("b1", 10_000))
        val rows = db.buildsFlow().first()
        assertEquals(1, rows.size)
        assertEquals(FinalStatus.SUCCESS, rows[0].finalStatus)
        assertEquals(Source.TERMINAL, rows[0].inferredSource)
        assertEquals(700L, rows[0].peakMemoryMb)
        assertEquals("Claude Code", rows[0].agent)
        assertEquals("Anthropic", rows[0].agentProvider)
    }

    @Test
    fun `sample with null heap round-trips and defaults source to unknown`(@TempDirArg tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val p = GradleProcess(
            pid = 5, parentPid = 1, type = ProcessType.GRADLE_DAEMON,
            commandLine = "java GradleDaemon", workingDirectory = "/p", projectPath = "/p",
            cpuPercent = null, rssMemoryMb = 300, maxHeapMb = null, minHeapMb = null,
            gc = null, startTimeMs = 1, status = "RUNNING",
            liveHeap = io.github.cdsap.daemonitor.domain.model.LiveJvmHeap.unavailable(1_000),
        )
        db.save(p, timestampMs = 1_000)
        val samples = db.samples(pid = 5, fromMs = 0, toMs = 2_000)
        assertEquals(1, samples.size)
        assertEquals(300L, samples[0].rssMemoryMb)
        val persisted = db.processSamplesForPid(5).single()
        assertEquals(false, persisted.heapAvailable)
        assertEquals(null, persisted.heapUsedMb)
        assertEquals(1_000L, persisted.heapSampledAtMs)
    }

    @Test
    fun `live heap metrics round-trip without overwriting rss or xmx`(@TempDirArg tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val p = GradleProcess(
            pid = 11,
            parentPid = 1,
            type = ProcessType.GRADLE_DAEMON,
            commandLine = "java -Xmx2g GradleDaemon",
            workingDirectory = "/p",
            projectPath = "/p",
            cpuPercent = 8.0,
            rssMemoryMb = 900,
            maxHeapMb = 2048,
            minHeapMb = null,
            gc = "G1",
            startTimeMs = 1,
            status = "RUNNING",
            liveHeap = io.github.cdsap.daemonitor.domain.model.LiveJvmHeap(
                usedMb = 410,
                committedMb = 640,
                maxMb = 2048,
                sampledAtMs = 2_000,
                available = true,
            ),
        )
        db.save(p, timestampMs = 2_000)
        val persisted = db.processSamplesForPid(11).single()
        assertEquals(900L, persisted.rssMemoryMb)
        assertEquals(2048L, persisted.maxHeapMb)
        assertEquals(true, persisted.heapAvailable)
        assertEquals(410L, persisted.heapUsedMb)
        assertEquals(640L, persisted.heapCommittedMb)
        assertEquals(2048L, persisted.heapMaxMb)
        assertEquals(2_000L, persisted.heapSampledAtMs)
    }

    @Test
    fun `extended cored metrics round-trip through local history`(@TempDirArg tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.save(
            GradleProcess(
                pid = 12,
                parentPid = 1,
                type = ProcessType.GRADLE_DAEMON,
                commandLine = "java GradleDaemon",
                workingDirectory = "/p",
                projectPath = "/p",
                cpuPercent = 1.0,
                rssMemoryMb = 256,
                maxHeapMb = null,
                minHeapMb = null,
                gc = "G1",
                startTimeMs = 1,
                status = "RUNNING",
                virtualMemoryMb = 1024,
                threadCount = 32,
                readBytes = 4096,
                majorPageFaults = 2,
                openFileDescriptors = 17,
                metaspaceUsedMb = 64,
                youngGcCount = 5,
                javaVersion = "21",
            ),
            timestampMs = 2_000,
        )

        val sample = db.processSamplesForPid(12).single()
        assertEquals(1024L, sample.virtualMemoryMb)
        assertEquals(32L, sample.threadCount)
        assertEquals(4096L, sample.readBytes)
        assertEquals(2L, sample.majorPageFaults)
        assertEquals(17L, sample.openFileDescriptors)
        assertEquals(64L, sample.metaspaceUsedMb)
        assertEquals(5L, sample.youngGcCount)
        assertEquals("21", sample.javaVersion)
        assertEquals(null, sample.swapMemoryMb)
    }

    @Test
    fun `kotlin daemon samples are persisted by process type`(@TempDirArg tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val p = GradleProcess(
            pid = 9,
            parentPid = 1,
            type = ProcessType.KOTLIN_DAEMON,
            commandLine = "java org.jetbrains.kotlin.daemon.KotlinCompileDaemon",
            workingDirectory = "/p",
            projectPath = "/p",
            cpuPercent = 12.5,
            rssMemoryMb = 512,
            maxHeapMb = 1500,
            minHeapMb = null,
            gc = "G1",
            startTimeMs = 1,
            status = "RUNNING",
        )

        db.save(p, timestampMs = 1_000)

        assertEquals(1L, db.countByType(ProcessType.KOTLIN_DAEMON))
        assertEquals(0L, db.countByType(ProcessType.GRADLE_DAEMON))
    }

    @Test
    fun `purge removes rows older than retention window`(@TempDirArg tmp: Path) = runTest {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        try {
            val now = 100L * 24 * 60 * 60 * 1000 // day 100
            val old = now - 8L * 24 * 60 * 60 * 1000 // 8 days ago (> 7d retention)
            val recent = now - 1L * 24 * 60 * 60 * 1000 // 1 day ago
            db.save(build("old", old))
            db.save(build("recent", recent))
            db.save(
                GradleProcess(
                    pid = 11,
                    parentPid = 1,
                    type = ProcessType.GRADLE_DAEMON,
                    commandLine = "java GradleDaemon",
                    workingDirectory = "/repo",
                    projectPath = "/repo",
                    cpuPercent = 1.0,
                    rssMemoryMb = 400,
                    maxHeapMb = 1024,
                    minHeapMb = null,
                    gc = null,
                    startTimeMs = 1,
                    status = "RUNNING",
                ),
                timestampMs = old,
            )
            db.save(
                GradleProcess(
                    pid = 11,
                    parentPid = 1,
                    type = ProcessType.GRADLE_DAEMON,
                    commandLine = "java GradleDaemon",
                    workingDirectory = "/repo",
                    projectPath = "/repo",
                    cpuPercent = 1.0,
                    rssMemoryMb = 400,
                    maxHeapMb = 1024,
                    minHeapMb = null,
                    gc = null,
                    startTimeMs = 1,
                    status = "RUNNING",
                ),
                timestampMs = recent,
            )
            db.purgeOlderThan(now, retentionDays = 7)
            val rows = db.buildsFlow().first()
            assertEquals(listOf("recent"), rows.map { it.buildId })
            assertEquals(1, db.recentSamples(limit = 10).size)
            assertEquals(recent, db.recentSamples(limit = 10).single().timestampMs)
        } finally {
            // VACUUM/incremental_vacuum keep Windows file locks until the JDBC driver closes.
            db.close()
        }
    }

    @Test
    fun `purge compacts released sqlite pages`() {
        // Avoid JUnit @TempDir: after VACUUM, Windows keeps SQLite locks and TempDir cleanup
        // fails the test with IOException even when assertions passed.
        val tmp = Files.createTempDirectory("daemonitor-compact-")
        val path = tmp.resolve("watcher.db")
        val db = WatcherDatabase.open(path)
        try {
            val now = 100L * 24 * 60 * 60 * 1000
            val old = now - 8L * 24 * 60 * 60 * 1000

            repeat(500) { index ->
                db.save(
                    sample(timestampMs = old + index, commandLine = "java GradleDaemon --sample=$index"),
                    old + index,
                )
            }
            val sizeBeforePurge = Files.size(path)

            db.purgeOlderThan(now, retentionDays = 7)

            val sizeAfterPurge = Files.size(path)
            assertTrue(
                sizeAfterPurge < sizeBeforePurge,
                "database did not shrink: $sizeBeforePurge -> $sizeAfterPurge",
            )
            assertTrue(db.freelistPageCount() < 100L, "freelist remains unexpectedly large")
            assertEquals(2L, db.autoVacuumMode(), "new databases should use incremental auto_vacuum")
        } finally {
            db.close()
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `purge migrates legacy none auto_vacuum and shrinks freelist`() {
        val tmp = Files.createTempDirectory("daemonitor-legacy-vacuum-")
        val path = tmp.resolve("watcher.db")
        // Simulate a pre-compaction database: schema without auto_vacuum=INCREMENTAL.
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            WatcherDb.Schema.create(driver)
        }

        val db = WatcherDatabase.open(path)
        try {
            assertEquals(0L, db.autoVacuumMode(), "legacy fixture should start with auto_vacuum=NONE")

            val now = 100L * 24 * 60 * 60 * 1000
            val old = now - 8L * 24 * 60 * 60 * 1000
            repeat(500) { index ->
                db.save(
                    sample(timestampMs = old + index, commandLine = "java GradleDaemon --legacy=$index"),
                    old + index,
                )
            }
            val sizeBeforePurge = Files.size(path)

            db.purgeOlderThan(now, retentionDays = 7)

            assertTrue(
                Files.size(path) < sizeBeforePurge,
                "legacy database did not shrink after purge",
            )
            assertTrue(db.freelistPageCount() < 100L, "legacy freelist remains unexpectedly large")
            assertEquals(2L, db.autoVacuumMode(), "legacy database should migrate to incremental auto_vacuum")
        } finally {
            db.close()
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `database file is created owner-only`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path).use {
            assertTrue(path.exists())
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (view != null) {
                val perms = view.readAttributes().permissions().map { it.name }
                assertTrue(perms.none { it.startsWith("GROUP") || it.startsWith("OTHERS") }, perms.toString())
            }
        }
    }

    @Test
    fun `open sets busy timeout for shared-file locks`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path).use { db ->
            assertEquals(5_000L, db.busyTimeoutMs())
        }
    }

    @Test
    fun `additive migration preserves legacy data and creates normalized tables`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path).use { it.save(build("legacy", 1_000)) }
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            listOf("collection_capabilities", "process_events", "metric_samples", "resource_scopes", "metric_definitions", "process_instances")
                .forEach { driver.execute(null, "DROP TABLE $it", 0) }
        }

        WatcherDatabase.open(path).use { db ->
            assertEquals(listOf("legacy"), db.recentBuilds().map { it.buildId })
            db.insertMetricDefinition(MetricDefinition("rss", "process.rss", "bytes", "gauge", "instant", "test"))
            db.upsertProcessInstance(
                io.github.cdsap.daemonitor.persistence.ProcessInstance("7:10", 7, ProcessType.GRADLE_DAEMON, firstObservedAtMs = 10, lastObservedAtMs = 10),
            )
            db.insertMetricSample(MetricSample(10, "7:10", null, "rss", intValue = Long.MAX_VALUE, realValue = null, "test", 10))
            assertEquals(Long.MAX_VALUE, db.metricSamples("rss").single().intValue)
        }
    }

    @Test
    fun `reused pid gets separate process instances`(@TempDirArg tmp: Path) {
        WatcherDatabase.open(tmp.resolve("watcher.db")).use { db ->
            db.save(sample(timestampMs = 1_000, commandLine = "first").copy(startTimeMs = 100), 1_000)
            db.save(sample(timestampMs = 2_000, commandLine = "second").copy(startTimeMs = 2_000), 2_000)
            assertEquals(listOf("5:100", "5:2000"), db.processInstancesForPid(5).map { it.instanceId })
        }
    }

    @Test
    fun `normalized metrics and events obey retention`(@TempDirArg tmp: Path) {
        WatcherDatabase.open(tmp.resolve("watcher.db")).use { db ->
            db.insertMetricDefinition(MetricDefinition("cpu", "process.cpu", "%", "gauge", "instant", "test"))
            db.upsertProcessInstance(io.github.cdsap.daemonitor.persistence.ProcessInstance("1:1", 1, ProcessType.GRADLE_DAEMON, firstObservedAtMs = 1, lastObservedAtMs = 1))
            db.insertMetricSample(MetricSample(1, "1:1", null, "cpu", null, 12.5, "test", 1))
            db.insertProcessEvent(ProcessEvent("1:1", 1, "started", "{}", "test"))
            db.insertMetricSample(MetricSample(100, "1:1", null, "cpu", null, 25.0, "test", 100))
            assertEquals(2, db.metricSamples("cpu").size)

            db.save(sample(timestampMs = 50, commandLine = "legacy"), 50)
            assertEquals(1, db.metricSamples(WatcherDatabase.LEGACY_RSS_METRIC).size)
            db.purgeOlderThan(100 + 8L * 24 * 60 * 60 * 1000, 7)
            assertEquals(0, db.metricSamples("cpu").size)
        }
    }

    @Test
    fun `closed database releases its driver`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        val db = WatcherDatabase.open(path)

        db.close()
        Files.delete(path)

        assertTrue(!path.exists())
    }

    @Test
    fun `repository ports expose build sample and retention operations`(@TempDirArg tmp: Path) {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        try {
            val builds: BuildRepository = database
            val samples: ProcessSampleRepository = database
            val retention: RetentionRepository = database

            samples.save(
                GradleProcess(
                    pid = 11,
                    parentPid = 1,
                    type = ProcessType.GRADLE_DAEMON,
                    commandLine = "java GradleDaemon",
                    workingDirectory = "/repo",
                    projectPath = "/repo",
                    cpuPercent = 1.0,
                    rssMemoryMb = 400,
                    maxHeapMb = 1024,
                    minHeapMb = null,
                    gc = null,
                    startTimeMs = 1,
                    status = "RUNNING",
                ),
                timestampMs = 5_000,
            )
            builds.save(build("port-build", 5_000, project = "/repo"))

            assertEquals(listOf("port-build"), builds.recent().map { it.buildId })
            assertEquals(listOf("/repo"), builds.distinctProjects())
            assertEquals(1, builds.search("port", limit = 10).size)
            assertEquals(1, builds.findByDaemon(1, limit = 10).size)
            assertEquals(
                listOf(BuildSample(rssMemoryMb = 400, cpuPercent = 1.0)),
                samples.samples(11, fromMs = 0, toMs = 10_000),
            )
            assertEquals(1, samples.findByPid(11, limit = 10).size)
            assertEquals(1, samples.recentSamples(limit = 10).size)

            retention.purgeOlderThan(nowMs = 5_000 + 8L * 24 * 60 * 60 * 1000, retentionDays = 7)
            assertTrue(builds.recent().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `purgeOlderThan succeeds against cored process lifecycle schema`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path).close()
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            driver.execute(null, "DROP TABLE process_events", 0)
            driver.execute(null, "DROP TABLE process_instances", 0)
        }
        seedCoredProcessLifecycle(path)

        WatcherDatabase.open(path).use { db ->
            db.purgeOlderThan(nowMs = 1 + 8L * 24 * 60 * 60 * 1000, retentionDays = 7)
        }

        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            assertEquals(0L, countRows(driver, "process_events"))
            assertEquals(0L, countRows(driver, "process_instances"))
        }
    }

    @Test
    fun `legacy process lifecycle tables migrate to cored column names`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path).close()
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            driver.execute(null, "DROP TABLE process_events", 0)
            driver.execute(null, "DROP TABLE process_instances", 0)
            driver.execute(
                null,
                """
                CREATE TABLE process_instances (
                  instance_id TEXT PRIMARY KEY,
                  pid INTEGER NOT NULL,
                  process_type TEXT NOT NULL,
                  config_json TEXT,
                  first_observed_at INTEGER NOT NULL,
                  last_observed_at INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE process_events (
                  event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  instance_id TEXT NOT NULL,
                  timestamp INTEGER NOT NULL,
                  event_type TEXT NOT NULL,
                  metadata_json TEXT,
                  source TEXT NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                "INSERT INTO process_instances VALUES ('1:1', 1, 'GRADLE_DAEMON', NULL, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO process_events(instance_id, timestamp, event_type, metadata_json, source) VALUES ('1:1', 1, 'started', '{}', 'test')",
                0,
            )
        }

        WatcherDatabase.open(path).use { db ->
            db.purgeOlderThan(nowMs = 1 + 8L * 24 * 60 * 60 * 1000, retentionDays = 7)
        }

        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            assertEquals(0L, countRows(driver, "process_events"))
            assertEquals(0L, countRows(driver, "process_instances"))
            assertTrue(tableColumns(driver, "process_events").contains("timestamp_ms"))
            assertTrue(tableColumns(driver, "process_instances").contains("last_observed_ms"))
        }
    }

    @Test
    fun `kotlin-created database reads cored observation sessions with mixed history`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path).close()
        seedCoredObservation(path)

        WatcherDatabase.open(path).use { database ->
            val history = (database as HistoryRepository).searchHistory("", limit = 10)
            assertEquals(listOf("build-1", "session-1"), history.map {
                when (it) {
                    is io.github.cdsap.daemonitor.application.ObservationSessionHistoryEntry -> it.name
                    is io.github.cdsap.daemonitor.application.BuildHistoryEntry -> it.build.buildId
                }
            })
            val session = history.filterIsInstance<io.github.cdsap.daemonitor.application.ObservationSessionHistoryEntry>().single()
            assertEquals(300L, session.peakRssMb)
            assertEquals(1L, session.successCount)
            assertEquals(1, session.processes.single().sampleCount)
        }
    }

    @Test
    fun `cored-created observation tables survive kotlin migration`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        seedCoredObservation(path, createTables = true)

        WatcherDatabase.open(path).use { database ->
            val session = database.searchHistory("session-1", 10).single()
                as io.github.cdsap.daemonitor.application.ObservationSessionHistoryEntry
            assertEquals("host-a", session.hostId)
            assertEquals(300L, session.processes.single().peakRssMb)
        }
    }

    private fun seedCoredProcessLifecycle(path: Path) {
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE process_instances (
                  id TEXT PRIMARY KEY NOT NULL,
                  host_id TEXT NOT NULL DEFAULT '',
                  pid INTEGER NOT NULL,
                  create_time_ms INTEGER NOT NULL,
                  process_type TEXT NOT NULL,
                  name TEXT,
                  command_line TEXT NOT NULL DEFAULT '',
                  first_observed_ms INTEGER NOT NULL,
                  last_observed_ms INTEGER NOT NULL,
                  disappeared_at_ms INTEGER,
                  UNIQUE(host_id, pid, create_time_ms)
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE process_events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  schema_version INTEGER NOT NULL DEFAULT 1,
                  event_type TEXT NOT NULL,
                  timestamp_ms INTEGER NOT NULL,
                  process_instance_id TEXT NOT NULL,
                  pid INTEGER NOT NULL,
                  process_type TEXT,
                  source TEXT NOT NULL DEFAULT 'process_poll',
                  metadata_json TEXT
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO process_instances(
                  id, host_id, pid, create_time_ms, process_type, command_line,
                  first_observed_ms, last_observed_ms
                ) VALUES ('host:1:1', 'host', 1, 1, 'GRADLE_DAEMON', 'java', 1, 1)
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO process_events(
                  schema_version, event_type, timestamp_ms, process_instance_id, pid, process_type, source, metadata_json
                ) VALUES (1, 'process.first_observed', 1, 'host:1:1', 1, 'GRADLE_DAEMON', 'process_poll', '{}')
                """.trimIndent(),
                0,
            )
        }
    }

    private fun seedCoredObservation(path: Path, createTables: Boolean = false) {
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            if (createTables) {
                driver.execute(null, "CREATE TABLE builds (build_id TEXT PRIMARY KEY, daemon_pid INTEGER NOT NULL, daemon_identity TEXT, command_line TEXT, working_directory TEXT, project_path TEXT, start_time INTEGER NOT NULL, end_time INTEGER, duration_seconds REAL, peak_memory_mb INTEGER, avg_memory_mb INTEGER, peak_cpu_percent REAL, inferred_source TEXT NOT NULL DEFAULT 'UNKNOWN', final_status TEXT NOT NULL, log_snippet TEXT, agent TEXT, agent_provider TEXT)", 0)
                driver.execute(null, "CREATE TABLE observation_sessions (name TEXT PRIMARY KEY NOT NULL, host_id TEXT NOT NULL, association TEXT NOT NULL, opened_at_ms INTEGER NOT NULL, closed_at_ms INTEGER, response_json TEXT)", 0)
                driver.execute(null, "CREATE TABLE observation_session_processes (session_name TEXT NOT NULL, host_id TEXT NOT NULL, pid INTEGER NOT NULL, start_time_ms INTEGER NOT NULL, PRIMARY KEY(session_name, host_id, pid, start_time_ms), FOREIGN KEY(session_name) REFERENCES observation_sessions(name))", 0)
                driver.execute(null, "CREATE TABLE observation_session_process_summaries (session_name TEXT NOT NULL, host_id TEXT NOT NULL, pid INTEGER NOT NULL, start_time_ms INTEGER NOT NULL, sample_count INTEGER NOT NULL, first_rss_mb INTEGER NOT NULL, peak_rss_mb INTEGER NOT NULL, last_rss_mb INTEGER NOT NULL, delta_rss_mb INTEGER NOT NULL, association TEXT NOT NULL, PRIMARY KEY(session_name, host_id, pid, start_time_ms), FOREIGN KEY(session_name) REFERENCES observation_sessions(name))", 0)
            }
            driver.execute(null, "INSERT INTO builds(build_id, daemon_pid, start_time, final_status) VALUES ('build-1', 7, 1500, 'SUCCESS')", 0)
            driver.execute(null, "INSERT INTO observation_sessions(name, host_id, association, opened_at_ms, closed_at_ms) VALUES ('session-1', 'host-a', 'confirmed', 1000, 2000)", 0)
            driver.execute(null, "INSERT INTO observation_session_process_summaries(session_name, host_id, pid, start_time_ms, sample_count, first_rss_mb, peak_rss_mb, last_rss_mb, delta_rss_mb, association) VALUES ('session-1', 'host-a', 42, 900, 1, 200, 300, 250, 50, 'confirmed')", 0)
        }
    }

    private fun countRows(driver: JdbcSqliteDriver, table: String): Long {
        val connection = driver.getConnection()
        try {
            return connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        } finally {
            driver.closeConnection(connection)
        }
    }

    private fun tableColumns(driver: JdbcSqliteDriver, table: String): Set<String> {
        val connection = driver.getConnection()
        try {
            return connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { resultSet ->
                    buildSet {
                        while (resultSet.next()) {
                            add(resultSet.getString("name"))
                        }
                    }
                }
            }
        } finally {
            driver.closeConnection(connection)
        }
    }

    private fun sample(timestampMs: Long, commandLine: String) = GradleProcess(
        pid = 5,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = commandLine,
        workingDirectory = "/p",
        projectPath = "/p",
        cpuPercent = 1.0,
        rssMemoryMb = 300,
        maxHeapMb = 1024,
        minHeapMb = null,
        gc = null,
        startTimeMs = timestampMs,
        status = "RUNNING",
    )
}

private typealias TempDirArg = org.junit.jupiter.api.io.TempDir
