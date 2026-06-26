package io.github.cdsap.daemonitor.ui.live

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LiveMonitorScreenUiTest {

    private fun sampleProcess() = GradleProcess(
        pid = 4321,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.5",
        workingDirectory = "/Users/dev/my-app",
        projectPath = "/Users/dev/my-app",
        cpuPercent = 12.0,
        rssMemoryMb = 1024,
        maxHeapMb = 4096,
        minHeapMb = 256,
        gc = "G1",
        startTimeMs = 1_700_000_000_000,
        status = "RUNNING",
        automated = false,
    )

    @Test
    fun `first render shows scanning state before the first poll`() = runComposeUiTest {
        mainClock.autoAdvance = false

        setContent { WatcherTheme { LiveMonitorScreen(LiveUiState(), onSelect = {}, onClearSelection = {}) } }

        onNodeWithText("Scanning for Gradle processes...").assertExists()
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
        setContent { WatcherTheme { LiveMonitorScreen(state, onSelect = {}, onClearSelection = {}) } }

        onNodeWithText("UPTIME").assertExists()        // new column header
        onNodeWithText("Gradle daemon").assertExists() // classified type label in the row
    }
}
