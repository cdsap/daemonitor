package io.github.cdsap.daemonitor.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
    fun `started timestamp includes minutes without ellipsis at default window size`() =
        runSkikoComposeUiTest(Size(1180f, 760f), Density(1f)) {
            val build = sampleBuild().copy(
                startTimeMs = java.time.ZonedDateTime.of(
                    2026, 11, 25, 14, 51, 32, 0, java.time.ZoneId.systemDefault(),
                ).toInstant().toEpochMilli(),
            )
            setContent {
                WatcherTheme {
                    HistoryScreen(HistoryUiState(builds = listOf(build), isEmptyResult = false), {}, {})
                }
            }
            onNodeWithText("Nov 25 14:51").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithTag("history-started-b1", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            kotlin.test.assertEquals(1, layouts.size)
            val layout = layouts.single()
            kotlin.test.assertTrue(layout.getLineRight(0) <= layout.size.width)
            kotlin.test.assertEquals("Nov 25 14:51".length, layout.getLineEnd(0, visibleEnd = true))
            kotlin.test.assertFalse(layouts.single().isLineEllipsized(0))
        }

    @Test
    fun `bazel UUID detail title ellipsizes before the status badge at default window size`() =
        runSkikoComposeUiTest(Size(1180f, 760f), Density(1f)) {
            val build = sampleBuild(buildId = "a08a35af-1a07-4345-a7ed-0239c4d748ce", tool = BuildTool.BAZEL)
            setContent {
                WatcherTheme {
                    HistoryScreen(HistoryUiState(builds = listOf(build), isEmptyResult = false), {}, {})
                }
            }
            onNodeWithText("my-app").performClick()
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithTag("history-detail-title").assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            kotlin.test.assertEquals(1, layouts.size)
            kotlin.test.assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
            val title = onNodeWithTag("history-detail-title").fetchSemanticsNode().boundsInRoot
            val status = onAllNodesWithText("success").onLast().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            kotlin.test.assertTrue(title.right <= status.left)
            onNodeWithText("Bazel build ${build.buildId}").assertExists()
        }

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
            "No builds match the current filters. Gradle rows come from daemon logs; Bazel rows appear when command.profile.gz or BEP is in reach.",
        ).assertExists()
        onNodeWithText(
            "Gradle from daemon logs; Bazel when command.profile.gz or BEP is in reach (Daemonitor never enables BEP).",
        ).assertExists()
    }

    @Test
    fun `bazel tool filter empty state is honest about profile and BEP`() = runComposeUiTest {
        val state = HistoryUiState(
            filter = HistoryFilter(tool = ToolFilter.BAZEL),
            builds = emptyList(),
            isEmptyResult = true,
        )
        setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }
        onNodeWithText(
            "No Bazel builds match the current filters. Bazel history appears when command.profile.gz or BEP is in reach (Daemonitor never enables BEP).",
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
    fun `bazel detail renders phase timings action cache memory deltas and agent`() = runComposeUiTest {
        val bazel = sampleBuild(
            buildId = "bz3",
            projectPath = "/ws/app",
            source = Source.BEP,
            agent = "Claude Code",
            tool = BuildTool.BAZEL,
        ).copy(
            bazelProfile = io.github.cdsap.daemonitor.domain.model.BazelProfileSummary(
                bazelVersion = "8.1.1",
                criticalPathSeconds = 3.2,
                criticalPathComponents = 7,
            ),
            bazelDetails = io.github.cdsap.daemonitor.domain.model.BazelDetailsSummary(
                bazelVersion = "8.1.1",
                analysisPhaseMs = 1_500,
                executionPhaseMs = 8_200,
                actionsExecuted = 55,
                remoteCacheHits = 40,
                localCacheHits = 10,
                localCacheMisses = 5,
                usedHeapPostBuildBytes = 314_572_800,
                workerCount = 2,
                testsTotal = 6,
                testsPassed = 5,
                testsFailed = 1,
                testsCached = 2,
            ),
            windowSummaries = listOf(
                io.github.cdsap.daemonitor.domain.model.BazelWindowSummary(
                    pid = 1234,
                    processType = "BAZEL_SERVER",
                    sampleCount = 4,
                    firstRssMb = 900,
                    peakRssMb = 1400,
                    lastRssMb = 1080,
                    deltaRssMb = 180,
                    firstHeapUsedMb = 300,
                    lastHeapUsedMb = 420,
                    deltaHeapUsedMb = 120,
                    deltaTotalGcCount = 5,
                ),
            ),
        )
        val state = HistoryUiState(builds = listOf(bazel), isEmptyResult = false)
        setContent { WatcherTheme(appearance = AppearancePreference.DARK) { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }

        onNodeWithText("app").performClick()
        waitForIdle()

        onNodeWithText("Bazel build detail").assertIsDisplayed()
        onNodeWithText("Phase timings").assertExists()
        onNodeWithText("1.5 s").assertExists()
        onNodeWithText("8.2 s").assertExists()
        onNodeWithText("Action cache").assertExists()
        onNodeWithText("40").assertExists()
        onNodeWithText("Memory & GC deltas").assertExists()
        onNodeWithText("900 → 1080 MB (+180 MB, peak 1400 MB)").assertExists()
        onNodeWithText("300 → 420 MB (+120 MB)").assertExists()
        onNodeWithText("+5 collections").assertExists()
        onNodeWithText("Workers & tests").assertExists()
        onNodeWithText("6 total (5 passed, 1 failed, 2 cached)").assertExists()
        onNodeWithText("(Anthropic)").assertExists()
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

    @Test
    fun `status and source pills have enough room for completed and terminal at 1180x760`() {
        val builds = listOf(
            sampleBuild(buildId = "g1", projectPath = "/ws/gradle-app", status = FinalStatus.SUCCESS, source = Source.TERMINAL),
            sampleBuild(buildId = "b1", projectPath = "/ws/bazel-app", status = FinalStatus.COMPLETED_NO_OUTCOME, source = Source.PROFILE, tool = BuildTool.BAZEL),
        )
        val state = HistoryUiState(builds = builds, isEmptyResult = false)
        androidx.compose.ui.test.runSkikoComposeUiTest(
            androidx.compose.ui.geometry.Size(1180f, 760f),
            androidx.compose.ui.unit.Density(1f),
        ) {
            setContent { WatcherTheme { HistoryScreen(state, onProject = {}, onTimeRange = {}) } }
            val completedBounds = onNodeWithText("completed").fetchSemanticsNode().boundsInRoot
            val terminalBounds = onNodeWithText("terminal").fetchSemanticsNode().boundsInRoot
            kotlin.test.assertTrue(completedBounds.width >= 55f, "completed pill too narrow at 1180dp: $completedBounds")
            kotlin.test.assertTrue(terminalBounds.width >= 44f, "terminal pill too narrow at 1180dp: $terminalBounds")
        }
    }
}
