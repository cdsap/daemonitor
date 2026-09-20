package io.github.cdsap.daemonitor.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
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
        assertEquals(300L, samples[0].first)
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
    fun `purge compacts released sqlite pages`(@TempDirArg tmp: Path) {
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
        }
    }

    @Test
    fun `purge migrates legacy none auto_vacuum and shrinks freelist`(@TempDirArg tmp: Path) {
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
        }
    }

    @Test
    fun `database file is created owner-only`(@TempDirArg tmp: Path) {
        val path = tmp.resolve("watcher.db")
        WatcherDatabase.open(path)
        assertTrue(path.exists())
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (view != null) {
            val perms = view.readAttributes().permissions().map { it.name }
            assertTrue(perms.none { it.startsWith("GROUP") || it.startsWith("OTHERS") }, perms.toString())
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
            assertEquals(listOf(400L to 1.0), samples.samples(11, fromMs = 0, toMs = 10_000))
            assertEquals(1, samples.findByPid(11, limit = 10).size)
            assertEquals(1, samples.recentSamples(limit = 10).size)

            retention.purgeOlderThan(nowMs = 5_000 + 8L * 24 * 60 * 60 * 1000, retentionDays = 7)
            assertTrue(builds.recent().isEmpty())
        } finally {
            database.close()
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
