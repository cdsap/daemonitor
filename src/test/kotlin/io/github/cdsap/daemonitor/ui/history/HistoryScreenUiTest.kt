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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildTool
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
        tool: BuildTool = BuildTool.GRADLE,
        logSnippet: String? = null,
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
        logSnippet = logSnippet,
        agent = agent,
        agentProvider = "Anthropic",
        tool = tool,
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
        onNodeWithText(
            "No builds match the current filters. Gradle rows come from daemon logs; Bazel rows appear only when BEP is already in reach.",
        ).assertExists()
        onNodeWithText(
            "Gradle from daemon logs; Bazel only when the client already emits BEP (Daemonitor never enables it).",
        ).assertExists()
    }

    @Test
    fun `bazel tool filter empty state is honest about BEP`() = runComposeUiTest {
        val state = HistoryUiState(
            filter = HistoryFilter(tool = ToolFilter.BAZEL),
            builds = emptyList(),
            isEmptyResult = true,
        )
        setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }
        onNodeWithText(
            "No Bazel builds match the current filters. Bazel history appears only when a client already emits BEP (Daemonitor never enables it).",
        ).assertExists()
    }

    @Test
    fun `gradle tool filter empty state does not mention bazel retention`() = runComposeUiTest {
        val state = HistoryUiState(
            filter = HistoryFilter(tool = ToolFilter.GRADLE),
            builds = emptyList(),
            isEmptyResult = true,
        )
        setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }
        onNodeWithText("No Gradle builds match the current filters. History is filled from Gradle daemon logs.").assertExists()
    }

    @Test
    fun `tool chips render and report the clicked tool`() = runComposeUiTest {
        val state = HistoryUiState(builds = listOf(sampleBuild()), isEmptyResult = false)
        var clicked: ToolFilter? = null
        setContent {
            WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}, onTool = { clicked = it }) }
        }

        onNodeWithText("All").assertExists()
        onNodeWithText("Gradle").assertExists()
        onNodeWithText("Bazel").assertExists()
        onNodeWithText("Bazel").performClick()
        kotlin.test.assertEquals(ToolFilter.BAZEL, clicked)
    }

    @Test
    fun `bazel row detail has no gradle daemon log excerpt or agent rows`() = runComposeUiTest {
        val bazel = sampleBuild(
            buildId = "bz1",
            projectPath = "/ws/app",
            source = Source.BEP,
            agent = null,
            tool = BuildTool.BAZEL,
        )
        val state = HistoryUiState(builds = listOf(bazel), isEmptyResult = false)
        setContent { WatcherTheme(appearance = AppearancePreference.DARK) { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }

        onNodeWithText("BEP").assertExists() // source pill in the row
        onNodeWithText("ws/app".substringAfterLast('/')).assertExists()
        onNodeWithText("app").performClick()
        waitForIdle()

        onNodeWithText("Bazel build detail").assertIsDisplayed()
        onNodeWithText("Bazel build bz1", substring = true).assertIsDisplayed()
        onNodeWithText("No failure snippet retained for this Bazel build.").assertExists()
        onNodeWithText("Gradle daemon log excerpt", substring = true).assertDoesNotExist()
        onNodeWithText("No Gradle daemon log captured", substring = true).assertDoesNotExist()
        onNodeWithText("Agent").assertDoesNotExist()
        onNodeWithText("LLM provider").assertDoesNotExist()
    }

    @Test
    fun `bazel detail shows retained failure snippet`() = runComposeUiTest {
        val bazel = sampleBuild(
            buildId = "bz2",
            projectPath = "/ws/app",
            source = Source.BEP,
            agent = null,
            tool = BuildTool.BAZEL,
            status = FinalStatus.FAILED,
            logSnippet = "ERROR: //app:lib failed",
        )
        val state = HistoryUiState(builds = listOf(bazel), isEmptyResult = false)
        setContent { WatcherTheme(appearance = AppearancePreference.DARK) { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }

        onNodeWithText("app").performClick()
        waitForIdle()
        onNodeWithText("ERROR: //app:lib failed", substring = true).assertExists()
        onNodeWithText("No failure snippet retained for this Bazel build.").assertDoesNotExist()
    }

    @Test
    fun `gradle row detail keeps the daemon log excerpt and agent rows`() = runComposeUiTest {
        val state = HistoryUiState(builds = listOf(sampleBuild()), isEmptyResult = false)
        setContent { WatcherTheme(appearance = AppearancePreference.DARK) { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }

        onNodeWithText("my-app").performClick()
        waitForIdle()
        onNodeWithText("Gradle build detail").assertIsDisplayed()
        onNodeWithText("Gradle daemon log excerpt", substring = true).assertExists()
        onNodeWithText("Agent").assertExists()
        onNodeWithText("LLM provider").assertExists()
    }

    @Test
    fun `time filters show the retention presets`() = runComposeUiTest {
        val state = HistoryUiState(builds = listOf(sampleBuild()), isEmptyResult = false)
        setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }

        listOf(7, 15, 30, 60, 90).forEach { days ->
            onNodeWithText("$days days").assertExists()
        }
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
        onNodeWithText("Gradle build b1", substring = true).assertExists()

        list.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()
        onNodeWithText("Gradle build b2", substring = true).assertExists()

        list.performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        waitForIdle()
        onNodeWithText("Gradle build b1", substring = true).assertExists()

        list.performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        waitForIdle()
        onNodeWithText("Gradle build b3", substring = true).assertExists()
    }
}
