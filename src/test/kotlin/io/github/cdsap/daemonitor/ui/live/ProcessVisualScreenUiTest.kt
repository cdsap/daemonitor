package io.github.cdsap.daemonitor.ui.live

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ProcessVisualScreenUiTest {

    @Test
    fun `visual screen renders memory mode timeline without tile strip`() = runVisualUiTest {
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, used = 700, cpu = 24.0),
            process(pid = 101, type = ProcessType.GRADLE_WORKER, project = "checkout", rss = 512, heap = null, used = null, cpu = 64.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes, timelineFor(processes)))
            }
        }

        onNodeWithText("Process visualizer").assertExists()
        onNodeWithTag("visual-dashboard").assertExists()
        onNodeWithTag("visual-metric-mode").assertExists()
        onNodeWithTag("visual-mode-memory").assertExists()
        onNodeWithText("RSS & Heap").assertDoesNotExist()
        onNodeWithText("SELECTED HEAP USED").assertDoesNotExist()
        onNodeWithText("SELECTED HEAP LIMIT").assertDoesNotExist()
        onNodeWithText("Overall RSS").assertDoesNotExist()
        onNodeWithText("Memory by process").assertDoesNotExist()
        onNodeWithText("Process inspector").assertDoesNotExist()
        onNodeWithText("Total 1536 MB RSS · 2 processes").assertExists()
        onNodeWithText("Total RSS").assertExists()
        onNodeWithText("Total heap used").assertExists()
        onNodeWithText("Total heap limit").assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · RSS").onFirst().assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · Heap used").onFirst().assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · Heap limit").onFirst().assertExists()
        onAllNodesWithText("Gradle worker · checkout · PID 101 · RSS").onFirst().assertExists()
        onNodeWithTag("visual-selected-vitals").assertExists()
        onNodeWithTag("visual-process-strip").assertExists()
    }

    @Test
    fun `metric mode switch shows io series instead of memory totals`() = runVisualUiTest {
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, used = 700, cpu = 24.0),
        )
        val timeline = timelineFor(processes).map { sample ->
            sample.copy(
                extendedByPid = mapOf(
                    100L to ProcessMetricSample(readBytes = 8_192, writeBytes = 1_024, threadCount = 42),
                ),
            )
        }

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes, timeline))
            }
        }

        onNodeWithTag("visual-mode-io").performClick()
        onNodeWithTag("visual-mode-io").assertExists()
        onNodeWithText("Total RSS").assertDoesNotExist()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · Read").onFirst().assertExists()
        onAllNodesWithText("Gradle daemon · checkout · PID 100 · Write").onFirst().assertExists()
    }

    @Test
    fun `selecting process strip updates selected vitals`() = runVisualUiTest {
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, used = 700, cpu = 24.0),
            process(pid = 101, type = ProcessType.KOTLIN_DAEMON, project = "design-system", rss = 768, heap = 1536, used = 400, cpu = 12.0),
        )

        setContent {
            WatcherTheme {
                ProcessVisualScreen(liveState(processes, timelineFor(processes)))
            }
        }

        // Default selection is the highest-RSS process (Gradle daemon · 4096 MB limit).
        onNodeWithText("PID 100 · RSS 1024 MB · Heap 700 MB · Limit 4096 MB", substring = true).assertExists()
        onNodeWithTag("visual-process-101").performClick()
        onNodeWithText("PID 101 · RSS 768 MB · Heap 400 MB · Limit 1536 MB", substring = true).assertExists()
    }

    @Test
    fun `visual screen preserves empty state`() = runVisualUiTest {
        setContent {
            WatcherTheme {
                ProcessVisualScreen(LiveUiState(processes = emptyList(), isLoading = false, isEmpty = true))
            }
        }

        onNodeWithText("No Gradle processes are running right now.").assertExists()
        onNodeWithText("Memory").assertDoesNotExist()
        onNodeWithTag("visual-dashboard").assertDoesNotExist()
    }

    @Test
    fun `range control switches to retained samples and preserves process selection`() = runVisualUiTest {
        val processes = listOf(
            process(pid = 100, type = ProcessType.GRADLE_DAEMON, project = "checkout", rss = 1024, heap = 4096, used = 700, cpu = 24.0),
            process(pid = 101, type = ProcessType.KOTLIN_DAEMON, project = "design-system", rss = 768, heap = 1536, used = 400, cpu = 12.0),
        )
        val visualState = androidx.compose.runtime.mutableStateOf(VisualTimelineState())

        setContent {
            WatcherTheme {
                ProcessVisualScreen(
                    state = liveState(processes, timelineFor(processes)),
                    visualState = visualState.value,
                    onTimeRange = { range ->
                        visualState.value = VisualTimelineState(
                            range = range,
                            isLoading = range != VisualTimeRange.LIVE,
                        )
                    },
                )
            }
        }

        onNodeWithTag("visual-process-101").performClick()
        onNodeWithText("PID 101 · RSS 768 MB · Heap 400 MB · Limit 1536 MB", substring = true).assertExists()
        onNodeWithText("15m").performClick()
        onNodeWithText("Loading retained process samples...").assertExists()
        visualState.value = VisualTimelineState(
            range = VisualTimeRange.FIFTEEN_MINUTES,
            samples = timelineFor(processes),
        )

        onNodeWithText("PID 101 · RSS 768 MB · Heap 400 MB · Limit 1536 MB", substring = true).assertExists()
        onAllNodesWithText("Kotlin daemon · design-system · PID 101 · RSS").onFirst().assertExists()
    }

    @Test
    fun `empty retained range shows explicit empty state`() = runVisualUiTest {
        setContent {
            WatcherTheme {
                ProcessVisualScreen(
                    state = LiveUiState(processes = emptyList(), isLoading = false, isEmpty = true),
                    visualState = VisualTimelineState(range = VisualTimeRange.ONE_HOUR),
                    onTimeRange = {},
                )
            }
        }

        onNodeWithText("Live").assertExists()
        onNodeWithText("15m").assertExists()
        onNodeWithText("1h").assertExists()
        onNodeWithText("No retained process samples in the selected range.").assertExists()
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
        val heapLimitByPid = processes.mapNotNull { process ->
            process.maxHeapMb?.let { heap -> process.pid to heap }
        }.toMap()
        val heapUsedByPid = processes.mapNotNull { process ->
            process.liveHeap?.takeIf { it.available }?.usedMb?.let { used -> process.pid to used }
        }.toMap()
        val total = processes.sumOf { it.rssMemoryMb }
        val end = 1_700_000_060_000
        return listOf(
            RssTimelineSample(
                atMs = end - 20_000,
                totalRssMb = total - 100,
                byPid = byPid,
                heapLimitByPid = heapLimitByPid,
                heapUsedByPid = heapUsedByPid,
            ),
            RssTimelineSample(
                atMs = end,
                totalRssMb = total,
                byPid = byPid,
                heapLimitByPid = heapLimitByPid,
                heapUsedByPid = heapUsedByPid,
            ),
        )
    }

    private fun process(
        pid: Long,
        type: ProcessType,
        project: String,
        rss: Long,
        heap: Long?,
        used: Long?,
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
        liveHeap = used?.let {
            LiveJvmHeap(
                usedMb = it,
                committedMb = it + 64,
                maxMb = heap,
                sampledAtMs = 1_700_000_000_000,
                available = true,
            )
        },
    )
}
