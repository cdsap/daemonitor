package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.domain.LiveMetricLabels
import io.github.cdsap.daemonitor.application.DaemonLog
import java.nio.file.Path
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.JvmJitMetrics
import io.github.cdsap.daemonitor.domain.model.JvmMemoryPool
import io.github.cdsap.daemonitor.domain.model.JvmRawValue
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LiveMonitorScreenUiTest {
    @Test
    fun `JVM pools and compilation appear only when metrics are available`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val empty = sampleProcess(type = ProcessType.JAVA_GRADLE_RELATED)
        val populated = empty.copy(
            memoryPools = listOf(JvmMemoryPool(
                "Eden", "jstat", JvmRawValue(0.0, "K"), null, null,
            )),
            codeCache = listOf(JvmMemoryPool(
                "CodeHeap", "jcmd", JvmRawValue(2.0, "M"), null, null,
            )),
            jit = JvmJitMetrics(0, null, null, 1.25),
        )
        var selected by mutableStateOf(empty)
        setContent {
            WatcherTheme {
                LiveMonitorScreen(LiveUiState(processes = listOf(selected), detail = DetailState.Selected(selected),
                    isLoading = false, isEmpty = false), onSelect = {}, onClearSelection = {})
            }
        }
        onNodeWithTag("process-detail-tab-jvm").performClick()
        onNodeWithText("JVM pools and compilation").assertDoesNotExist()
        selected = populated
        waitForIdle()
        onNodeWithText("JVM pools and compilation").assertExists()
        onNodeWithText("Eden").assertExists()
        onNodeWithText("used 0.0 K · committed unavailable · max unavailable").assertExists()
        onNodeWithText("Code cache · CodeHeap").assertExists()
        onNodeWithText("0 compiled, 1.250 s compilation time").assertExists()
    }

    @Test
    fun `inspector switches categories and retains identity with daemon only tab`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val daemon = sampleProcess()
        var state by mutableStateOf(LiveUiState(processes = listOf(daemon), detail = DetailState.Selected(daemon), isLoading = false, isEmpty = false))
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }
        onNodeWithTag("process-detail-vitals").assertExists()
        onNodeWithText("Virtual memory").assertExists()
        onNodeWithText("Working dir").assertDoesNotExist()
        onNodeWithTag("process-detail-tab-overview").assertIsSelected()
        onNodeWithTag("process-detail-tab-jvm").assertIsNotSelected()
        onNodeWithTag("process-detail-tab-jvm").performClick()
        onNodeWithTag("process-detail-tab-jvm").assertIsSelected()
        onNodeWithTag("process-detail-tab-overview").assertIsNotSelected()
        onNodeWithText("Java runtime").assertExists()
        onNodeWithText("Virtual memory").assertDoesNotExist()
        onNodeWithTag("process-detail-tab-os-io").performClick()
        onNodeWithText("Threads").assertExists()
        onNodeWithText("Read operations").assertExists()
        onNodeWithTag("process-detail-tab-process").performClick()
        onNodeWithText("Working dir").assertExists()
        onNodeWithText("Command line").assertExists()
        onNodeWithTag("process-detail-vitals").assertExists()
        onNodeWithTag("process-detail-tab-gradle").performClick()
        onNodeWithText("Recent builds").assertExists()
        val worker = daemon.copy(type = ProcessType.GRADLE_WORKER)
        state = state.copy(processes = listOf(worker), detail = DetailState.Selected(worker))
        onNodeWithTag("process-detail-tab-gradle").assertDoesNotExist()
        onNodeWithText("Virtual memory").assertExists()
    }

    @Test
    fun `inspector keeps selected tab across live metric updates for the same pid`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val first = sampleProcess(pid = 42, rssMemoryMb = 256)
        var state by mutableStateOf(
            LiveUiState(processes = listOf(first), detail = DetailState.Selected(first), isLoading = false, isEmpty = false),
        )
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        onNodeWithTag("process-detail-tab-jvm").performClick()
        onNodeWithTag("process-detail-tab-jvm").assertIsSelected()
        onNodeWithText("Java runtime").assertExists()

        val refreshed = first.copy(rssMemoryMb = 512, cpuPercent = 18.0)
        state = state.copy(processes = listOf(refreshed), detail = DetailState.Selected(refreshed))
        waitForIdle()

        onNodeWithTag("process-detail-tab-jvm").assertIsSelected()
        onNodeWithTag("process-detail-tab-overview").assertIsNotSelected()
        onNodeWithText("Java runtime").assertExists()
        onNodeWithTag("process-detail-vitals").assertTextEquals("RSS 512 MB · CPU 18% · Heap n/a")
    }

    @Test
    fun `native memory details show measurements and unavailable values`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess().copy(
            proportionalBytes = 123,
            privateCommittedBytes = 0,
            physicalFootprintBytes = 1024,
            peakResidentBytes = 1048576,
        )
        var selected by mutableStateOf(process)
        setContent {
            WatcherTheme {
                LiveMonitorScreen(
                    LiveUiState(processes = listOf(selected), detail = DetailState.Selected(selected), isLoading = false, isEmpty = false),
                    onSelect = {}, onClearSelection = {},
                )
            }
        }

        val rows = listOf(
            "Proportional (PSS)" to "123 B",
            "Private committed" to "0 B",
            "Physical footprint" to "1.0 KB",
            "Peak resident" to "1.0 MB",
        )
        rows.forEach { (label, value) ->
            onNode(hasAnyChild(hasText(label)) and hasAnyChild(hasText(value)), useUnmergedTree = true).assertExists()
        }

        selected = sampleProcess()
        rows.forEach { (label, _) ->
            onNode(hasAnyChild(hasText(label)) and hasAnyChild(hasText("unavailable")), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `daemon log metadata displays values and explicit missing state only for Gradle daemons`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val daemon = sampleProcess()
        val logPath = Path.of("/tmp/daemon-4321.out.log")
        var state by mutableStateOf(LiveUiState(
            processes = listOf(daemon), detail = DetailState.Selected(daemon),
            daemonLog = DaemonLog(daemon.pid, "8.14.3", logPath),
            tailState = LogTailState.Available(listOf("tail line")), isLoading = false, isEmpty = false,
        ))
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }
        onNodeWithText("Gradle version").assertExists()
        onNodeWithText("8.14.3").assertExists()
        onNodeWithText(logPath.toString()).assertExists()
        onNodeWithText("tail line").assertExists()
        state = state.copy(daemonLog = null)
        assertEquals(2, onAllNodesWithText("Unavailable").fetchSemanticsNodes().size)
        val worker = daemon.copy(type = ProcessType.GRADLE_WORKER)
        state = state.copy(processes = listOf(worker), detail = DetailState.Selected(worker))
        onNodeWithText("Gradle version").assertDoesNotExist()
        onNodeWithText("Log path").assertDoesNotExist()
    }

    @Test
    fun `bazel server selection shows bazel label icon and detail title`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val bazel = sampleProcess(
            pid = 30030,
            type = ProcessType.BAZEL_SERVER,
            commandLine = "java -jar /x/A-server.jar --product_name=Bazel --workspace_directory=/tmp/ws",
            projectPath = "/tmp/ws",
            maxHeapMb = null,
            gc = null,
        )
        val state = LiveUiState(
            processes = listOf(bazel),
            summary = LiveSummary(
                activeProcessCount = 1,
                totalRssMb = 1024,
                highestMemoryPid = 30030,
                activeProjectCount = 1,
            ),
            detail = DetailState.Selected(bazel),
            isLoading = false,
            isEmpty = false,
        )
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                Box(Modifier.size(width = 900.dp, height = 380.dp)) {
                    LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
                }
            }
        }

        onNodeWithText("Bazel server / Process detail").assertIsDisplayed()
        onNodeWithTag("process-type-icon-BAZEL_SERVER").assertExists()
        onNodeWithText("Gradle version").assertDoesNotExist()
        onNodeWithText(ProcessType.BAZEL_SERVER.displayLabel(), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `process details displays host and capability availability`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess()
        val state = LiveUiState(
            processes = listOf(process), detail = DetailState.Selected(process), isLoading = false, isEmpty = false,
            host = ContextState.Loaded(io.github.cdsap.daemonitor.application.HostContext(100, 8, 0.0, 1048576, 2097152)),
            capabilities = ContextState.Loaded(listOf(
                io.github.cdsap.daemonitor.application.MetricCapability("host.cpu.usage_percent", "available"),
                io.github.cdsap.daemonitor.application.MetricCapability("host.cpu.other", "unsupported"),
            )),
        )
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }
        onNodeWithTag("process-detail-tab-os-io").performClick()
        onNodeWithText("Host context").assertExists()
        onNodeWithText("0.0%").assertExists()
        onNodeWithText("1 / 2 available").assertExists()
    }

    private fun sampleProcess(
        pid: Long = 4321,
        parentPid: Long = 1,
        type: ProcessType = ProcessType.GRADLE_DAEMON,
        commandLine: String = "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.5",
        maxHeapMb: Long? = 4096,
        cpuPercent: Double? = 12.0,
        rssMemoryMb: Long = 1024,
        workingDirectory: String? = "/Users/dev/my-app",
        projectPath: String? = workingDirectory,
        gc: String? = "G1",
        status: String = "RUNNING",
        liveHeap: LiveJvmHeap? = null,
    ) = GradleProcess(
        pid = pid,
        parentPid = parentPid,
        type = type,
        commandLine = commandLine,
        workingDirectory = workingDirectory,
        projectPath = projectPath,
        cpuPercent = cpuPercent,
        rssMemoryMb = rssMemoryMb,
        maxHeapMb = maxHeapMb,
        minHeapMb = 256,
        gc = gc,
        youngGcTimeSeconds = 1.234,
        fullGcTimeSeconds = 2.345,
        concurrentGcTimeSeconds = 3.456,
        totalGcTimeSeconds = 7.035,
        javaVersion = "21.0.8",
        javaRuntimeVersion = "21.0.8+9-LTS",
        javaVendor = "Eclipse Adoptium",
        javaVmName = "OpenJDK 64-Bit Server VM",
        javaVmVersion = "21.0.8+9-LTS",
        osName = "Linux",
        osArch = "amd64",
        activeProcessorCount = 8,
        startTimeMs = 1_700_000_000_000,
        status = status,
        automated = false,
        liveHeap = liveHeap,
    )

    @Test
    fun `recent builds section distinguishes states and only appears for Gradle daemons`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val daemon = sampleProcess()
        var state by mutableStateOf(LiveUiState(processes = listOf(daemon), detail = DetailState.Selected(daemon), recentBuilds = RecentBuildsState.Loading, isLoading = false, isEmpty = false))
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }
        onNodeWithTag("process-detail-tab-gradle").performClick()
        onNodeWithText("Loading recent builds…").assertExists()
        state = state.copy(recentBuilds = RecentBuildsState.Loaded(emptyList()))
        onNodeWithText("No recent builds for this daemon.").assertExists()
        val build = Build("b1", daemon.pid, null, null, null, "/project", 1_700_000_000_000, null, 12.5, null, null, null, Source.TERMINAL, FinalStatus.SUCCESS, null, agent = "agent")
        state = state.copy(recentBuilds = RecentBuildsState.Loaded(listOf(build)))
        onNodeWithText("SUCCESS").assertExists()
        onNodeWithText("/project · 12.5 s", substring = true).assertExists()
        onNodeWithText("TERMINAL / agent", substring = true).assertExists()
        state = state.copy(recentBuilds = RecentBuildsState.Unavailable)
        onNodeWithText("Recent builds unavailable.").assertExists()
        val other = sampleProcess(type = ProcessType.KOTLIN_DAEMON)
        state = state.copy(processes = listOf(other), detail = DetailState.Selected(other))
        onNodeWithText("Recent builds").assertDoesNotExist()
    }

    @Test
    fun `first render shows scanning state before the first poll`() = runComposeUiTest {
        mainClock.autoAdvance = false

        setContent { WatcherTheme { LiveMonitorScreen(LiveUiState(), onSelect = {}, onClearSelection = {}) } }

        onNodeWithText("Scanning for Gradle processes...").assertExists()
    }

    @Test
    fun `poll failure replaces monitoring status with degraded indicator`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val state = LiveUiState(
            isLoading = false,
            pollError = PollError(failedAtMs = 1234, errorType = "IOException"),
        )

        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        onNodeWithText("DEGRADED").assertExists()
        onNodeWithText("MONITORING").assertDoesNotExist()
    }

    @Test
    fun `process table shows the uptime column and the daemon row`() = runComposeUiTest {
        // The screen runs a 1s wall-clock ticker (infinite delay loop); freezing the test clock
        // keeps the composition idle so node queries don't spin.
        mainClock.autoAdvance = false

        val state = LiveUiState(
            processes = listOf(sampleProcess()),
            summary = LiveSummary(activeProcessCount = 1, totalRssMb = 1024, highestMemoryPid = 4321, activeProjectCount = 1),
            isLoading = false,
            isEmpty = false,
        )
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
            }
        }

        onNodeWithText("UPTIME").assertExists()        // new column header
        onNodeWithText("Gradle daemon").assertExists() // classified type label in the row
    }

    @Test
    fun `first-poll cpu shows sampling and wrapper heap explains not probed`() = runComposeUiTest {
        mainClock.autoAdvance = false

        val wrapper = sampleProcess(
            pid = 99,
            type = ProcessType.GRADLE_WRAPPER,
            commandLine = "java org.gradle.wrapper.GradleWrapperMain test",
            cpuPercent = null,
            liveHeap = LiveJvmHeap.unavailable(1_000L),
        )
        val idleDaemon = sampleProcess(
            pid = 100,
            cpuPercent = 0.0,
            liveHeap = LiveJvmHeap(
                usedMb = 400,
                committedMb = 512,
                maxMb = 4096,
                sampledAtMs = 1_000L,
                available = true,
            ),
        )
        val state = LiveUiState(
            processes = listOf(wrapper, idleDaemon),
            summary = LiveSummary(activeProcessCount = 2, totalRssMb = 2048, highestMemoryPid = 99, activeProjectCount = 1),
            detail = DetailState.Selected(wrapper),
            isLoading = false,
            isEmpty = false,
        )

        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
            }
        }

        onNodeWithText(LiveMetricLabels.CPU_SAMPLING_COMPACT, useUnmergedTree = true).assertExists()
        onNodeWithText("0%", useUnmergedTree = true).assertExists()
        onNodeWithText(LiveMetricLabels.HEAP_UNAVAILABLE_COMPACT, useUnmergedTree = true).assertExists()
        onNodeWithText("400 MB", useUnmergedTree = true).assertExists()
        onNodeWithText(LiveMetricLabels.cpuDetail(null), useUnmergedTree = true).assertExists()
        onNodeWithText(
            LiveMetricLabels.liveHeapUsedDetail(wrapper.liveHeap, wrapper.type),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `process details show available and missing OS activity independently`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess().copy(
            cpuUserSecondsTotal = 10.25,
            cpuSystemSecondsTotal = null,
            cpuTotalSecondsTotal = 13.0,
            cpuCoresUsed = 0.0,
            logicalProcessorCount = 8,
        )
        val state = LiveUiState(
            processes = listOf(process),
            detail = DetailState.Selected(process),
            isLoading = false,
            isEmpty = false,
        )
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        listOf(
            "CPU user time" to "10.250 s",
            "CPU system time" to "unavailable",
            "CPU total time" to "13.000 s",
            "Cores used" to "0.000",
            "Logical processors" to "8",
        ).forEach { (label, value) ->
            onNodeWithText(label, useUnmergedTree = true).onParent().assert(hasAnyChild(hasText(value)))
        }
    }

    @Test
    fun `missing OS activity is unavailable in ended process details`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess()
        val state = LiveUiState(
            detail = DetailState.Ended(process),
            isLoading = false,
            isEmpty = false,
        )
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        listOf("CPU user time", "CPU system time", "CPU total time", "Cores used", "Logical processors")
            .forEach { label ->
                onNodeWithText(label, useUnmergedTree = true).onParent().assert(hasAnyChild(hasText("unavailable")))
            }
    }

    @Test
    fun `process details shows JVM and operating system metadata`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess()
        val state = LiveUiState(
            processes = listOf(process),
            summary = LiveSummary(activeProcessCount = 1, totalRssMb = 1024, highestMemoryPid = process.pid, activeProjectCount = 1),
            detail = DetailState.Selected(process),
            isLoading = false,
            isEmpty = false,
        )

        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        onNodeWithTag("process-detail-tab-jvm").performClick()
        onNodeWithText("Java runtime").assertExists()
        onNodeWithText("Java VM").assertExists()
        onNodeWithText("Java VM version").assertExists()
        onNodeWithText("OS").assertExists()
        onNodeWithText("OS arch").assertExists()
        onNodeWithText("YGCT").assertExists()
        onNodeWithText("1.234 s").assertExists()
        onNodeWithText("GCT").assertExists()
        onNodeWithText("7.035 s").assertExists()
    }

    @Test
    fun `process details show CPU accounting including zero and unavailable values`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess().copy(
            cpuUserSecondsTotal = 0.0,
            cpuSystemSecondsTotal = 2.75,
            cpuTotalSecondsTotal = 2.75,
            cpuCoresUsed = null,
            resourceScope = "process",
        )
        val state = LiveUiState(processes = listOf(process), detail = DetailState.Selected(process), isLoading = false, isEmpty = false)
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        onNodeWithText("CPU user time").assertExists()
        onNodeWithText("0.000 s").assertExists()
        onNodeWithText("CPU system time").assertExists()
        onNodeWithText("CPU total time").assertExists()
        onNodeWithText("Cores used").assert(hasAnySibling(hasText("unavailable")))
        onNodeWithText("Resource scope").assertExists()
        onNodeWithText("process").assertExists()
        assertEquals(2, onAllNodesWithText("2.750 s").fetchSemanticsNodes().size)
    }

    @Test
    fun `process details shows current child process snapshot`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val parent = sampleProcess(pid = 100)
        val child = sampleProcess(
            pid = 200,
            parentPid = parent.pid,
            type = ProcessType.GRADLE_WORKER,
            projectPath = "/Users/dev/child-project",
            workingDirectory = "/Users/dev/child-project",
            rssMemoryMb = 256,
            cpuPercent = 7.0,
            liveHeap = LiveJvmHeap(
                usedMb = 64,
                committedMb = 128,
                maxMb = 512,
                sampledAtMs = 2_000,
                available = true,
            ),
        )
        val childWithMissingValues = sampleProcess(
            pid = 201,
            parentPid = parent.pid,
            cpuPercent = null,
            maxHeapMb = null,
            workingDirectory = null,
            projectPath = null,
            gc = null,
            status = "",
        )
        val state = LiveUiState(
            processes = listOf(parent, child, childWithMissingValues),
            summary = LiveSummary(activeProcessCount = 3, totalRssMb = 2304, highestMemoryPid = parent.pid, activeProjectCount = 2),
            detail = DetailState.Selected(parent),
            isLoading = false,
            isEmpty = false,
        )

        setContent {
            WatcherTheme {
                Box(Modifier.size(width = 900.dp, height = 600.dp)) {
                    LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
                }
            }
        }

        onNodeWithTag("child-processes").assertExists()
        onNodeWithText("Gradle worker · PID 200").assertExists()
        onNodeWithText("RSS 256 MB · CPU 7% · RUNNING").assertExists()
        onNodeWithText("Project /Users/dev/child-project").assertExists()
        onNodeWithText("Heap 64 MB · GC G1").assertExists()
        onNodeWithText("RSS 1024 MB · CPU … · —").assertExists()
        onNodeWithText("Work dir —").assertExists()
        onNodeWithText("Heap n/a · GC —").assertExists()
    }

    @Test
    fun `leaf process details omit child process pane`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val process = sampleProcess(pid = 100)
        val state = LiveUiState(
            processes = listOf(process),
            summary = LiveSummary(activeProcessCount = 1, totalRssMb = 1024, highestMemoryPid = process.pid, activeProjectCount = 1),
            detail = DetailState.Selected(process),
            isLoading = false,
            isEmpty = false,
        )

        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        onNodeWithTag("child-processes").assertDoesNotExist()
        onNodeWithText("CHILD PROCESSES").assertDoesNotExist()
    }

    @Test
    fun `child process pane refreshes with the current snapshot`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val parent = sampleProcess(pid = 100)
        val firstChild = sampleProcess(pid = 200, parentPid = parent.pid, rssMemoryMb = 256)
        val secondChild = sampleProcess(pid = 300, parentPid = parent.pid, rssMemoryMb = 384)
        var state by mutableStateOf(
            LiveUiState(
                processes = listOf(parent, firstChild),
                summary = LiveSummary(activeProcessCount = 2, totalRssMb = 1280, highestMemoryPid = parent.pid, activeProjectCount = 1),
                detail = DetailState.Selected(parent),
                isLoading = false,
                isEmpty = false,
            ),
        )

        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }
        onNodeWithText("Gradle daemon · PID 200").assertExists()

        state = state.copy(
            processes = listOf(parent, secondChild),
            summary = state.summary.copy(totalRssMb = 1408),
            detail = DetailState.Selected(parent),
        )
        waitForIdle()

        onNodeWithText("Gradle daemon · PID 200").assertDoesNotExist()
        onNodeWithText("Gradle daemon · PID 300").assertExists()
        onNodeWithText("RSS 384 MB · CPU 12% · RUNNING").assertExists()
    }

    @Test
    fun `child process pane remains usable in a narrow layout`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val parent = sampleProcess(pid = 100)
        val child = sampleProcess(pid = 200, parentPid = parent.pid, rssMemoryMb = 256)
        val state = LiveUiState(
            processes = listOf(parent, child),
            summary = LiveSummary(activeProcessCount = 2, totalRssMb = 1280, highestMemoryPid = parent.pid, activeProjectCount = 1),
            detail = DetailState.Selected(parent),
            isLoading = false,
            isEmpty = false,
        )

        setContent {
            WatcherTheme {
                Box(Modifier.size(width = 500.dp, height = 600.dp)) {
                    LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
                }
            }
        }

        onNodeWithTag("child-processes").assertExists()
        onNodeWithText("Gradle daemon · PID 200").assertExists()
    }

    @Test
    fun `live monitor keeps memory graph out of the process table tab`() = runComposeUiTest {
        mainClock.autoAdvance = false

        val state = LiveUiState(
            processes = listOf(sampleProcess()),
            summary = LiveSummary(activeProcessCount = 1, totalRssMb = 1024, highestMemoryPid = 4321, activeProjectCount = 1),
            isLoading = false,
            isEmpty = false,
        )
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
            }
        }

        onNodeWithText("Memory allocation").assertDoesNotExist()
        onNodeWithText("UPTIME").assertExists()
        onNodeWithText("Process monitor").assertExists()
        onNodeWithText("Gradle daemon").assertExists()
    }

    @Test
    fun `overflowing process detail content can be scrolled to the end`() = runComposeUiTest {
        mainClock.autoAdvance = false

        val longCommandLine = buildString {
            append("java org.gradle.launcher.daemon.bootstrap.GradleDaemon")
            repeat(120) { index -> append(" -Ddaemonitor.test.$index=value$index") }
        }
        val process = sampleProcess(commandLine = longCommandLine)
        val state = LiveUiState(
            processes = listOf(process),
            summary = LiveSummary(activeProcessCount = 1, totalRssMb = 1024, highestMemoryPid = 4321, activeProjectCount = 1),
            detail = DetailState.Selected(process),
            isLoading = false,
            isEmpty = false,
        )

        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                Box(Modifier.size(width = 900.dp, height = 600.dp)) {
                    LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
                }
            }
        }

        val hasScrollableDetailContent = SemanticsMatcher("has overflowing vertical scroll range") { node ->
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            range != null && range.maxValue() > 0f
        }
        val hasScrolledDetailContent = SemanticsMatcher("has advanced vertical scroll range") { node ->
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            range != null && range.value() > 0f
        }

        onNodeWithTag("process-detail-tab-process").performClick()
        val detailPane = onNodeWithTag("process-detail-scroll")
        detailPane.assert(hasScrollableDetailContent)
        detailPane.performTouchInput { swipeUp() }
        detailPane.assert(hasScrolledDetailContent)
        onNodeWithText("UPTIME").assertIsDisplayed()
        onNodeWithText("Daemon log").assertIsDisplayed()
    }

    @Test
    fun `arrow keys cycle the selected process`() = runComposeUiTest {
        mainClock.autoAdvance = false

        val processes = listOf(
            sampleProcess(pid = 100, type = ProcessType.GRADLE_DAEMON),
            sampleProcess(pid = 200, type = ProcessType.GRADLE_WRAPPER),
            sampleProcess(pid = 300, type = ProcessType.KOTLIN_DAEMON),
        )
        val state = LiveUiState(
            processes = processes,
            summary = LiveSummary(activeProcessCount = 3, totalRssMb = 3072, highestMemoryPid = 100, activeProjectCount = 1),
            isLoading = false,
            isEmpty = false,
        )
        var selectedPid by mutableStateOf<Long?>(null)

        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                LiveMonitorScreen(
                    state = state.copy(
                        detail = selectedPid?.let { pid ->
                            DetailState.Selected(processes.first { it.pid == pid })
                        } ?: DetailState.NoSelection,
                    ),
                    onSelect = { selectedPid = it },
                    onClearSelection = { selectedPid = null },
                )
            }
        }

        val list = onNodeWithTag("live-process-list")
        list.requestFocus()
        waitForIdle()
        list.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()
        assertEquals(100L, selectedPid)

        list.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()
        assertEquals(200L, selectedPid)

        list.performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        waitForIdle()
        assertEquals(100L, selectedPid)

        list.performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        waitForIdle()
        assertEquals(300L, selectedPid) // wraps
    }

    @Test
    fun `process table shows PID so peak memory PID can be matched without opening detail`() = runComposeUiTest {
        mainClock.autoAdvance = false

        val peak = sampleProcess(pid = 6227, type = ProcessType.GRADLE_DAEMON)
        val other = sampleProcess(pid = 1001, type = ProcessType.KOTLIN_DAEMON, rssMemoryMb = 512)
        val state = LiveUiState(
            processes = listOf(peak, other),
            summary = LiveSummary(
                activeProcessCount = 2,
                totalRssMb = 1536,
                highestMemoryPid = 6227,
                activeProjectCount = 1,
            ),
            isLoading = false,
            isEmpty = false,
        )
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
            }
        }

        onNodeWithText("PID").assertExists()
        // Peak tile and the matching table cell both show the PID.
        assertTrue(onAllNodesWithText("6227").fetchSemanticsNodes().size >= 2)
        onNodeWithText("PEAK MEMORY PID").assertExists()
        onNodeWithTag("peak-memory-pid-tile").assertExists()
    }

    @Test
    fun `clicking peak memory PID tile selects that process`() = runComposeUiTest {
        mainClock.autoAdvance = false

        val peak = sampleProcess(pid = 6227, type = ProcessType.GRADLE_DAEMON)
        val other = sampleProcess(pid = 1001, type = ProcessType.KOTLIN_DAEMON, rssMemoryMb = 512)
        val processes = listOf(peak, other)
        val state = LiveUiState(
            processes = processes,
            summary = LiveSummary(
                activeProcessCount = 2,
                totalRssMb = 1536,
                highestMemoryPid = 6227,
                activeProjectCount = 1,
            ),
            isLoading = false,
            isEmpty = false,
        )
        var selectedPid by mutableStateOf<Long?>(null)

        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                LiveMonitorScreen(
                    state = state.copy(
                        detail = selectedPid?.let { pid ->
                            DetailState.Selected(processes.first { it.pid == pid })
                        } ?: DetailState.NoSelection,
                    ),
                    onSelect = { selectedPid = it },
                    onClearSelection = { selectedPid = null },
                )
            }
        }

        assertNull(selectedPid)
        onNodeWithTag("peak-memory-pid-tile").assertExists().performClick()
        waitForIdle()
        assertEquals(6227L, selectedPid)
        onNodeWithText("PID 6227 · Gradle daemon", substring = true).assertExists()
    }

    @Test
    fun `grouped mode shows daemon aggregate and nested child rows`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val daemon = sampleProcess(pid = 100, rssMemoryMb = 1_000)
        val worker = sampleProcess(
            pid = 200,
            parentPid = daemon.pid,
            type = ProcessType.GRADLE_WORKER,
            rssMemoryMb = 250,
        )
        val state = LiveUiState(
            processes = listOf(daemon, worker),
            summary = LiveSummary(activeProcessCount = 2, totalRssMb = 1_250, highestMemoryPid = daemon.pid, activeProjectCount = 1),
            isLoading = false,
            isEmpty = false,
        )

        setContent {
            WatcherTheme {
                Box(Modifier.size(width = 900.dp, height = 600.dp)) {
                    LiveMonitorScreen(state, onSelect = {}, onClearSelection = {})
                }
            }
        }

        onNodeWithText("Grouped").performClick()
        waitForIdle()

        onNodeWithText("▾", useUnmergedTree = true).assertExists()
        onNodeWithText("Gradle worker", useUnmergedTree = true).assertExists()
        onNodeWithText("PID").assertExists()
    }
}
