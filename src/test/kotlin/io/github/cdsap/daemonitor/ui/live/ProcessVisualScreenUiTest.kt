package io.github.cdsap.daemonitor.ui.live

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ProcessVisualScreenUiTest {

    @Test
    fun `visual screen renders process map and inspector metrics`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, cpu = 24.0),
            process(pid = 101, type = ProcessType.TEST_WORKER, project = "checkout", rss = 512, heap = null, cpu = 64.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes))
            }
        }

        onNodeWithText("Process visualizer").assertExists()
        onNodeWithText("Process map").assertExists()
        onNodeWithText("Process inspector").assertExists()
        onNodeWithText("RSS 1024 MB").assertExists()
        onNodeWithText("Heap limit 4096 MB").assertExists()
        onNodeWithText("CPU 24%").assertExists()
        onNodeWithText("Heap limit unavailable").assertExists()
    }

    @Test
    fun `selecting process map row updates inspector`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, cpu = 24.0),
            process(pid = 101, type = ProcessType.KOTLIN_DAEMON, project = "design-system", rss = 768, heap = 1536, cpu = 12.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes))
            }
        }

        onNodeWithText("Kotlin daemon · PID 101").performClick()

        onNodeWithText("PID 101").assertExists()
        onNodeWithText("java -Xmx1536m design-system").assertExists()
    }

    @Test
    fun `visual screen preserves empty state`() = runComposeUiTest {
        mainClock.autoAdvance = false

        setContent {
            WatcherTheme {
                ProcessVisualScreen(LiveUiState(processes = emptyList(), isLoading = false, isEmpty = true))
            }
        }

        onNodeWithText("No Gradle processes are running right now.").assertExists()
        onNodeWithText("Process map").assertDoesNotExist()
    }

    private fun liveState(processes: List<GradleProcess>) = LiveUiState(
        processes = processes,
        summary = LiveSummary(
            activeProcessCount = processes.size,
            totalRssMb = processes.sumOf { it.rssMemoryMb },
            highestMemoryPid = processes.maxByOrNull { it.rssMemoryMb }?.pid,
            activeProjectCount = processes.mapNotNull { it.projectPath }.distinct().size,
        ),
        isLoading = false,
        isEmpty = processes.isEmpty(),
    )

    private fun process(
        pid: Long,
        type: ProcessType,
        project: String,
        rss: Long,
        heap: Long?,
        cpu: Double,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = type,
        commandLine = "java -Xmx${heap ?: 512}m $project",
        workingDirectory = "/workspace/$project",
        projectPath = "/workspace/$project",
        cpuPercent = cpu,
        rssMemoryMb = rss,
        maxHeapMb = heap,
        minHeapMb = null,
        gc = "G1",
        startTimeMs = 1_700_000_000_000,
        status = "RUNNING",
    )
}
