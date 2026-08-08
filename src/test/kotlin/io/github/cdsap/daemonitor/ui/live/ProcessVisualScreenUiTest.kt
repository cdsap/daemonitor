package io.github.cdsap.daemonitor.ui.live

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
    fun `visual screen renders rss and heap timeline series`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, cpu = 24.0),
            process(pid = 101, type = ProcessType.TEST_WORKER, project = "checkout", rss = 512, heap = null, cpu = 64.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes, timelineFor(processes)))
            }
        }

        onNodeWithText("Process visualizer").assertExists()
        onNodeWithText("RSS & Heap").assertExists()
        onNodeWithText("Overall RSS").assertDoesNotExist()
        onNodeWithText("Memory by process").assertDoesNotExist()
        onNodeWithText("Process inspector").assertDoesNotExist()
        onNodeWithText("Total 1536 MB RSS · 2 processes").assertExists()
        onNodeWithText("Total RSS").assertExists()
        onNodeWithText("Total Heap").assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · RSS").onFirst().assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · Heap").onFirst().assertExists()
        onAllNodesWithText("Test worker · checkout · PID 101 · RSS").onFirst().assertExists()
        onNodeWithText("SELECTED HEAP").assertExists()
    }

    @Test
    fun `selecting timeline legend updates selected heap tile`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, cpu = 24.0),
            process(pid = 101, type = ProcessType.KOTLIN_DAEMON, project = "design-system", rss = 768, heap = 1536, cpu = 12.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes, timelineFor(processes)))
            }
        }

        onAllNodesWithText("Kotlin daemon · design-system · PID 101 · Heap").onFirst().performClick()

        onNodeWithText("1536 MB").assertExists()
        onNodeWithText("Process inspector").assertDoesNotExist()
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
        onNodeWithText("RSS & Heap").assertDoesNotExist()
        onNodeWithText("Process inspector").assertDoesNotExist()
    }

    private fun liveState(
        processes: List<GradleProcess>,
        rssTimeline: List<RssTimelineSample> = emptyList(),
    ) = LiveUiState(
        processes = processes,
        summary = LiveSummary(
            activeProcessCount = processes.size,
            totalRssMb = processes.sumOf { it.rssMemoryMb },
            highestMemoryPid = processes.maxByOrNull { it.rssMemoryMb }?.pid,
            activeProjectCount = processes.mapNotNull { it.projectPath }.distinct().size,
        ),
        isLoading = false,
        isEmpty = processes.isEmpty(),
        rssTimeline = rssTimeline,
    )

    private fun timelineFor(processes: List<GradleProcess>): List<RssTimelineSample> {
        val byPid = processes.associate { it.pid to it.rssMemoryMb }
        val heapByPid = processes.mapNotNull { process ->
            process.maxHeapMb?.let { heap -> process.pid to heap }
        }.toMap()
        val total = processes.sumOf { it.rssMemoryMb }
        val end = 1_700_000_060_000
        return listOf(
            RssTimelineSample(atMs = end - 20_000, totalRssMb = total - 100, byPid = byPid, heapByPid = heapByPid),
            RssTimelineSample(atMs = end, totalRssMb = total, byPid = byPid, heapByPid = heapByPid),
        )
    }

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
