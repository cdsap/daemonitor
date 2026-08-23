package io.github.cdsap.daemonitor.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HistoryScreenUiTest {

    private fun sampleBuild(
        buildId: String = "b1",
        projectPath: String = "/Users/dev/my-app",
        status: FinalStatus = FinalStatus.SUCCESS,
        source: Source = Source.TERMINAL,
        agent: String? = "Claude Code",
    ) = Build(
        buildId = buildId,
        daemonPid = 1234,
        daemonIdentity = "uid-1",
        commandLine = null,
        workingDirectory = projectPath,
        projectPath = projectPath,
        startTimeMs = 1_700_000_000_000,
        endTimeMs = 1_700_000_030_000,
        durationSeconds = 30.0,
        peakMemoryMb = 2048,
        avgMemoryMb = 1500,
        peakCpuPercent = 80.0,
        inferredSource = source,
        finalStatus = status,
        logSnippet = null,
        agent = agent,
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
    fun `ide build rows suppress stale stored agent labels`() = runComposeUiTest {
        val state = HistoryUiState(
            builds = listOf(sampleBuild().copy(inferredSource = Source.IDE)),
            isEmptyResult = false,
        )
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                HistoryScreen(state, onProject = {}, onTimeRange = {})
            }
        }

        onNodeWithText("IDE").assertExists()
        onNodeWithText("Claude Code").assertDoesNotExist()
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

    @Test
    fun `arrow keys cycle the selected build and show its detail`() = runComposeUiTest {
        val builds = listOf(
            sampleBuild(buildId = "b1", projectPath = "/Users/dev/alpha"),
            sampleBuild(buildId = "b2", projectPath = "/Users/dev/beta", status = FinalStatus.FAILED),
            sampleBuild(buildId = "b3", projectPath = "/Users/dev/gamma", status = FinalStatus.INTERRUPTED),
        )
        val state = HistoryUiState(builds = builds, isEmptyResult = false)
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                HistoryScreen(state, onProject = {}, onTimeRange = {})
            }
        }

        val list = onNodeWithTag("history-build-list")
        list.requestFocus()
        waitForIdle()
        list.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()
        onNodeWithText("Build b1").assertExists()

        list.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()
        onNodeWithText("Build b2").assertExists()

        list.performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        waitForIdle()
        onNodeWithText("Build b1").assertExists()

        list.performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        waitForIdle()
        onNodeWithText("Build b3").assertExists()
    }
}
