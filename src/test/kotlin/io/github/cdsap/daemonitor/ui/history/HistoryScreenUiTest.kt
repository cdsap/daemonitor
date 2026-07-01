package io.github.cdsap.daemonitor.ui.history

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HistoryScreenUiTest {

    private fun sampleBuild() = Build(
        buildId = "b1",
        daemonPid = 1234,
        daemonIdentity = "uid-1",
        commandLine = null,
        workingDirectory = "/Users/dev/my-app",
        projectPath = "/Users/dev/my-app",
        startTimeMs = 1_700_000_000_000,
        endTimeMs = 1_700_000_030_000,
        durationSeconds = 30.0,
        peakMemoryMb = 2048,
        avgMemoryMb = 1500,
        peakCpuPercent = 80.0,
        inferredSource = Source.TERMINAL,
        finalStatus = FinalStatus.SUCCESS,
        logSnippet = null,
        agent = "Claude Code",
        agentProvider = "Anthropic",
    )

    @Test
    fun `a build row renders project, status pill and agent`() = runComposeUiTest {
        val state = HistoryUiState(builds = listOf(sampleBuild()), isEmptyResult = false)
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                HistoryScreen(state, onProject = {}, onTimeRange = {})
            }
        }

        onNodeWithText("my-app").assertExists()      // project (leaf of the path)
        onNodeWithText("success").assertExists()     // colored status pill
        onNodeWithText("Claude Code").assertExists() // agent label
    }

    @Test
    fun `empty result shows the empty state`() = runComposeUiTest {
        val state = HistoryUiState(builds = emptyList(), isEmptyResult = true)
        setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }
        onNodeWithText("No builds match the current filters.").assertExists()
    }

    @Test
    fun `time filters show the retention presets`() = runComposeUiTest {
        val state = HistoryUiState(builds = listOf(sampleBuild()), isEmptyResult = false)
        setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }

        listOf(7, 15, 30, 60, 90).forEach { days ->
            onNodeWithText("$days days").assertExists()
        }
        onNodeWithText("All").assertDoesNotExist()
        onNodeWithText("Today").assertDoesNotExist()
        onNodeWithText("Last 24 hours").assertDoesNotExist()
    }
}
