package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultDaemonitorQueryServiceTest {

    @Test
    fun `searchHistory delegates to retained builds`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.insertBuild(build("old", 1_000, project = "/repo/a"))
        db.insertBuild(build("match", 2_000, project = "/repo/target", status = FinalStatus.FAILED))
        val queries = DefaultDaemonitorQueryService(db, ProcessSource { emptyList() })

        val builds = queries.searchHistory("target", limit = 50)

        assertEquals(listOf("match"), builds.map { it.buildId })
        assertEquals(FinalStatus.FAILED, builds.single().finalStatus)
    }

    @Test
    fun `buildsForProcess matches pid builds and samples`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.insertBuild(build("b1", 3_000, pid = 42, project = "/repo/target"))
        db.insertSample(
            GradleProcess(
                pid = 42,
                parentPid = 7,
                type = ProcessType.GRADLE_DAEMON,
                commandLine = "java GradleDaemon",
                workingDirectory = "/repo/target",
                projectPath = "/repo/target",
                cpuPercent = 12.0,
                rssMemoryMb = 512,
                maxHeapMb = 2048,
                minHeapMb = null,
                gc = "G1",
                startTimeMs = 1_000,
                status = "RUNNING",
            ),
            timestampMs = 3_100,
        )
        val queries = DefaultDaemonitorQueryService(db, ProcessSource { emptyList() })

        val result = queries.buildsForProcess("42", limit = 50)

        assertEquals("42", result.process)
        assertEquals(listOf("b1"), result.matchedBuilds.map { it.buildId })
        assertEquals(listOf(42L), result.matchedProcessSamples.map { it.pid })
    }

    @Test
    fun `buildsForProcess text match filters recent samples`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.insertBuild(build("keep", 4_000, project = "/repo/target"))
        db.insertSample(sample(pid = 10, project = "/repo/target"), timestampMs = 4_100)
        db.insertSample(sample(pid = 11, project = "/repo/other"), timestampMs = 4_200)
        val queries = DefaultDaemonitorQueryService(db, ProcessSource { emptyList() })

        val result = queries.buildsForProcess("target", limit = 50)

        assertEquals(listOf("keep"), result.matchedBuilds.map { it.buildId })
        assertEquals(listOf(10L), result.matchedProcessSamples.map { it.pid })
    }

    @Test
    fun `currentProcesses uses process source`(@TempDir tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val process = sample(pid = 99, project = "/repo", type = ProcessType.KOTLIN_DAEMON)
        val queries = DefaultDaemonitorQueryService(db, ProcessSource { listOf(process) })

        assertEquals(listOf(99L), queries.currentProcesses().map { it.pid })
    }

    private fun build(
        id: String,
        startMs: Long,
        pid: Long = 1,
        project: String = "/repo",
        status: FinalStatus = FinalStatus.SUCCESS,
    ) = Build(
        buildId = id,
        daemonPid = pid,
        daemonIdentity = "uid-$pid",
        commandLine = "gradlew build",
        workingDirectory = project,
        projectPath = project,
        startTimeMs = startMs,
        endTimeMs = startMs + 1000,
        durationSeconds = 1.0,
        peakMemoryMb = 700,
        avgMemoryMb = 600,
        peakCpuPercent = 50.0,
        inferredSource = Source.TERMINAL,
        finalStatus = status,
        logSnippet = "BUILD ${if (status == FinalStatus.SUCCESS) "SUCCESSFUL" else "FAILED"}",
        agent = null,
        agentProvider = null,
    )

    private fun sample(
        pid: Long,
        project: String,
        type: ProcessType = ProcessType.GRADLE_DAEMON,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = type,
        commandLine = "java $type",
        workingDirectory = project,
        projectPath = project,
        cpuPercent = 1.0,
        rssMemoryMb = 256,
        maxHeapMb = 1024,
        minHeapMb = null,
        gc = null,
        startTimeMs = 1,
        status = "RUNNING",
    )
}
