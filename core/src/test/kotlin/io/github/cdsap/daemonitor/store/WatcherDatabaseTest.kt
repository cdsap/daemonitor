package io.github.cdsap.daemonitor.store

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
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
        )
        db.save(p, timestampMs = 1_000)
        val samples = db.samples(pid = 5, fromMs = 0, toMs = 2_000)
        assertEquals(1, samples.size)
        assertEquals(300L, samples[0].first)
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
        val now = 100L * 24 * 60 * 60 * 1000 // day 100
        val old = now - 8L * 24 * 60 * 60 * 1000 // 8 days ago (> 7d retention)
        val recent = now - 1L * 24 * 60 * 60 * 1000 // 1 day ago
        db.save(build("old", old))
        db.save(build("recent", recent))
        db.purgeOlderThan(now, retentionDays = 7)
        val rows = db.buildsFlow().first()
        assertEquals(listOf("recent"), rows.map { it.buildId })
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
    }
}

private typealias TempDirArg = org.junit.jupiter.api.io.TempDir
