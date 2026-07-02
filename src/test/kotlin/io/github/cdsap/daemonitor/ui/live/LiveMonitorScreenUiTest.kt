package io.github.cdsap.daemonitor.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LiveMonitorScreenUiTest {

    private fun sampleProcess(commandLine: String = "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.5") = GradleProcess(
        pid = 4321,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = commandLine,
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
                Box(Modifier.size(width = 900.dp, height = 380.dp)) {
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

        val detailPane = onNodeWithTag("process-detail-scroll")
        detailPane.assert(hasScrollableDetailContent)
        detailPane.performTouchInput { swipeUp() }
        detailPane.assert(hasScrolledDetailContent)
        onNodeWithText("UPTIME").assertIsDisplayed()
        onNodeWithText("Daemon log").assertIsDisplayed()
    }
}
