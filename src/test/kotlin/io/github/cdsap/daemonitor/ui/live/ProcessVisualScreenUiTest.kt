package io.github.cdsap.daemonitor.ui.live

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ProcessVisualScreenUiTest {

    @Test
    fun `visual screen renders rss and heap timeline series`() = runVisualUiTest {
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
        onNodeWithText("Live").assertExists()
        onNodeWithText("15 min").assertExists()
        onNodeWithText("1 hour").assertExists()
        onNodeWithText("All retained").assertExists()
        onNodeWithText("Live · Total 1536 MB RSS · 2 processes").assertExists()
        onNodeWithText("Solid = RSS, dashed = configured heap limit (-Xmx). Click a series to show or hide it. Last sample available").assertExists()
        onNodeWithText("Total RSS").assertExists()
        onNodeWithText("Total Heap").assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · RSS").onFirst().assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · Heap").onFirst().assertExists()
        onAllNodesWithText("Test worker · checkout · PID 101 · RSS").onFirst().assertExists()
        onNodeWithText("HEAP LIMIT").assertExists()
    }

    @Test
    fun `historic empty range renders explicit no samples state`() = runVisualUiTest {
        setContent {
            WatcherTheme {
                ProcessVisualScreen(
                    state = LiveUiState(processes = emptyList(), isLoading = false, isEmpty = true),
                    visualState = VisualUiState(
                        selectedRange = VisualRange.ONE_HOUR,
                        isLoading = false,
                        isEmpty = true,
                        statusText = "No samples in this range",
                    ),
                )
            }
        }

        onNodeWithText("Live").assertExists()
        onNodeWithText("15 min").assertExists()
        onNodeWithText("1 hour").assertExists()
        onNodeWithText("All retained").assertExists()
        onNodeWithText("No samples in this range").assertExists()
    }

    @Test
    fun `selecting timeline legend updates selected heap tile`() = runVisualUiTest {
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, cpu = 24.0),
            process(pid = 101, type = ProcessType.KOTLIN_DAEMON, project = "design-system", rss = 768, heap = 1536, cpu = 12.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes, timelineFor(processes)))
            }
        }

        // Default selection is the highest-RSS process.
        onNodeWithText("4096 MB").assertExists()
        onAllNodesWithText("Kotlin daemon · design-system · PID 101 · Heap").onFirst().performClick()

        onNodeWithText("1536 MB").assertExists()
        onNodeWithText("4096 MB").assertDoesNotExist()
        onNodeWithText("Process inspector").assertDoesNotExist()
    }

    @Test
    fun `visual screen preserves empty state`() = runVisualUiTest {
        setContent {
            WatcherTheme {
                ProcessVisualScreen(LiveUiState(processes = emptyList(), isLoading = false, isEmpty = true))
            }
        }

        onNodeWithText("No Gradle processes are running right now.").assertExists()
        onNodeWithText("RSS & Heap").assertDoesNotExist()
        onNodeWithText("Process inspector").assertDoesNotExist()
    }

    private fun runVisualUiTest(block: androidx.compose.ui.test.SkikoComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(Size(1400f, 900f), Density(1f)) {
            // Keep the Compose clock paused: VisualDashboard hosts a 30s refresh loop.
            mainClock.autoAdvance = false
            block()
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
