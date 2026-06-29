package io.github.cdsap.daemonitor.ui.history

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryViewModelTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 100L * dayMs // fixed "now"

    private fun build(id: String, startMs: Long, project: String) = Build(
        buildId = id, daemonPid = 1, daemonIdentity = "uid", commandLine = null,
        workingDirectory = project, projectPath = project, startTimeMs = startMs,
        endTimeMs = startMs + 1000, durationSeconds = 1.0, peakMemoryMb = 100,
        avgMemoryMb = 100, peakCpuPercent = 10.0, inferredSource = Source.TERMINAL,
        finalStatus = FinalStatus.SUCCESS, logSnippet = null,
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
        assertEquals(Defaults.RETENTION_PRESETS, TimeRange.entries.map(TimeRange::days))
        assertEquals(Defaults.DEFAULT_RETENTION_DAYS, TimeRange.DEFAULT.days)
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
}
