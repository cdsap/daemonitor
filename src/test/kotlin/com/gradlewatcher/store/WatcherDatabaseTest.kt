package com.gradlewatcher.store

import com.gradlewatcher.domain.model.Build
import com.gradlewatcher.domain.model.FinalStatus
import com.gradlewatcher.domain.model.GradleProcess
import com.gradlewatcher.domain.model.ProcessType
import com.gradlewatcher.domain.model.Source
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
        db.insertBuild(build("b1", 10_000))
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
        db.insertSample(p, timestampMs = 1_000)
        val samples = db.samplesInWindow(pid = 5, startMs = 0, endMs = 2_000)
        assertEquals(1, samples.size)
        assertEquals(300L, samples[0].first)
    }

    @Test
    fun `purge removes rows older than retention window`(@TempDirArg tmp: Path) = runTest {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val now = 100L * 24 * 60 * 60 * 1000 // day 100
        val old = now - 8L * 24 * 60 * 60 * 1000 // 8 days ago (> 7d retention)
        val recent = now - 1L * 24 * 60 * 60 * 1000 // 1 day ago
        db.insertBuild(build("old", old))
        db.insertBuild(build("recent", recent))
        db.purgeOlderThan(now)
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
}

private typealias TempDirArg = org.junit.jupiter.api.io.TempDir
