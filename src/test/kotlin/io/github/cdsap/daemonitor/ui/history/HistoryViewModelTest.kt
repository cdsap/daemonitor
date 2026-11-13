package io.github.cdsap.daemonitor.ui.history

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildTool
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryViewModelTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 100L * dayMs // fixed "now"

    private fun build(
        id: String,
        startMs: Long,
        project: String,
        tool: BuildTool = BuildTool.GRADLE,
    ) = Build(
        buildId = id, daemonPid = 1, daemonIdentity = "uid", commandLine = null,
        workingDirectory = project, projectPath = project, startTimeMs = startMs,
        endTimeMs = startMs + 1000, durationSeconds = 1.0, peakMemoryMb = 100,
        avgMemoryMb = 100, peakCpuPercent = 10.0, inferredSource = Source.TERMINAL,
        finalStatus = FinalStatus.SUCCESS, logSnippet = null, tool = tool,
    )

    private fun vm() = HistoryViewModel(now = { now })

    @Test
    fun `project filter narrows to selected project`() {
        val vm = vm()
        vm.onBuilds(listOf(build("a", now, "/x"), build("b", now, "/y"), build("c", now, "/x")))
        vm.setProject("/x")
        assertEquals(listOf("a", "c"), vm.state.value.builds.map { it.buildId })
    }

    @Test
    fun `time-range presets match retention choices and use rolling day cutoffs`() {
        assertEquals(RetentionPolicy.DEFAULT.presets, TimeRange.entries.map(TimeRange::days))
        assertEquals(RetentionPolicy.DEFAULT.defaultDays, TimeRange.DEFAULT.days)
        TimeRange.entries.forEach { range ->
            assertEquals(now - range.days * dayMs, range.cutoffMs(now))
        }
    }

    @Test
    fun `seven-day preset excludes an older build`() {
        val vm = vm()
        vm.setTimeRange(TimeRange.LAST_7_DAYS)
        val olderThanSevenDays = now - 7 * dayMs - 1
        vm.onBuilds(listOf(build("old", olderThanSevenDays, "/x"), build("recent", now, "/x")))
        assertEquals(listOf("recent"), vm.state.value.builds.map { it.buildId })
    }

    @Test
    fun `project and time-range compound with AND`() {
        val vm = vm()
        vm.setTimeRange(TimeRange.LAST_7_DAYS)
        val old = now - 7 * dayMs - 1
        vm.onBuilds(
            listOf(
                build("x-recent", now, "/x"),
                build("y-recent", now, "/y"),
                build("x-old", old, "/x"),
            ),
        )
        vm.setProject("/x")
        assertEquals(listOf("x-recent"), vm.state.value.builds.map { it.buildId })
    }

    @Test
    fun `no matching builds sets empty-result flag`() {
        val vm = vm()
        vm.onBuilds(listOf(build("a", now, "/x")))
        vm.setProject("/nonexistent")
        assertTrue(vm.state.value.isEmptyResult)
    }

    @Test
    fun `tool filter keeps only bazel rows`() {
        val vm = vm()
        vm.onBuilds(
            listOf(
                build("g", now, "/x", BuildTool.GRADLE),
                build("b", now, "/x", BuildTool.BAZEL),
            ),
        )
        vm.setTool(ToolFilter.BAZEL)
        assertEquals(listOf("b"), vm.state.value.builds.map { it.buildId })
    }

    @Test
    fun `tool filter defaults to all and keeps both tools`() {
        val vm = vm()
        vm.onBuilds(listOf(build("g", now, "/x", BuildTool.GRADLE), build("b", now, "/x", BuildTool.BAZEL)))
        assertEquals(ToolFilter.ALL, vm.state.value.filter.tool)
        assertEquals(listOf("g", "b"), vm.state.value.builds.map { it.buildId })
    }

    @Test
    fun `gradle tool filter hides bazel rows`() {
        val vm = vm()
        vm.onBuilds(listOf(build("g", now, "/x", BuildTool.GRADLE), build("b", now, "/x", BuildTool.BAZEL)))
        vm.setTool(ToolFilter.GRADLE)
        assertEquals(listOf("g"), vm.state.value.builds.map { it.buildId })
    }

    @Test
    fun `tool filter compounds with project and flags empty result`() {
        val vm = vm()
        vm.onBuilds(listOf(build("g", now, "/x", BuildTool.GRADLE), build("b", now, "/y", BuildTool.BAZEL)))
        vm.setTool(ToolFilter.BAZEL)
        vm.setProject("/x")
        assertTrue(vm.state.value.isEmptyResult)
        vm.setProject("/y")
        assertEquals(listOf("b"), vm.state.value.builds.map { it.buildId })
    }
}
