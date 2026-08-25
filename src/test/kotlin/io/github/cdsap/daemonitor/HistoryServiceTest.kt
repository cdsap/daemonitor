package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryServiceTest {
    @Test
    fun `history and projects read from the database`(@TempDir tmp: Path) {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        try {
            database.insertBuild(build("b1", startMs = 1_000, project = "/proj/a"))
            database.insertBuild(build("b2", startMs = 3_000, project = "/proj/b"))

            val service = HistoryService(database)

            assertEquals(listOf("b2", "b1"), service.history().map { it.buildId })
            assertEquals(listOf("/proj/a", "/proj/b"), service.projects().sorted())
        } finally {
            database.close()
        }
    }

    private fun build(id: String, startMs: Long, project: String) = Build(
        buildId = id,
        daemonPid = 1,
        daemonIdentity = "uid-1",
        commandLine = "gradlew build",
        workingDirectory = project,
        projectPath = project,
        startTimeMs = startMs,
        endTimeMs = startMs + 3_000,
        durationSeconds = 3.0,
        peakMemoryMb = 700,
        avgMemoryMb = 600,
        peakCpuPercent = 50.0,
        inferredSource = Source.TERMINAL,
        finalStatus = FinalStatus.SUCCESS,
        logSnippet = "BUILD SUCCESSFUL in 3s",
        agent = null,
        agentProvider = null,
    )
}
