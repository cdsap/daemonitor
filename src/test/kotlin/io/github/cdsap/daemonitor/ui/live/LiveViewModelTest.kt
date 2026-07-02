package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveViewModelTest {

    private fun proc(pid: Long, rss: Long = 100, project: String? = "/p", cwd: String? = "/p") =
        GradleProcess(
            pid = pid, parentPid = 1, type = ProcessType.GRADLE_DAEMON,
            commandLine = "java GradleDaemon", workingDirectory = cwd, projectPath = project,
            cpuPercent = 10.0, rssMemoryMb = rss, maxHeapMb = 512, minHeapMb = null,
            gc = "G1", startTimeMs = 1, status = "RUNNING",
        )

    @Test
    fun `initial state is loading until first poll completes`() {
        val vm = LiveViewModel()

        assertTrue(vm.state.value.isLoading)

        vm.onPoll(emptyList())

        assertTrue(!vm.state.value.isLoading)
    }

    @Test
    fun `poll populates rows and summary`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1, rss = 100, project = "/a"), proc(2, rss = 300, project = "/b"), proc(3, rss = 50, project = "/a")))
        val s = vm.state.value
        assertEquals(3, s.processes.size)
        assertEquals(450L, s.summary.totalRssMb)
        assertEquals(2L, s.summary.highestMemoryPid)
        assertEquals(2, s.summary.activeProjectCount)
        assertTrue(!s.isEmpty)
    }

    @Test
    fun `summary excludes processes without project attribution`() {
        val vm = LiveViewModel()
        vm.onPoll(
            listOf(
                proc(1, project = "/project"),
                proc(2, project = null, cwd = "/Users/dev/.gradle/daemon/8.14.3"),
                proc(3, project = null, cwd = "/Users/dev/.kotlin/daemon"),
            ),
        )

        assertEquals(1, vm.state.value.summary.activeProjectCount)
    }

    @Test
    fun `selecting then disappearing transitions to ended`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1), proc(2)))
        vm.select(1)
        assertTrue(vm.state.value.detail is DetailState.Selected)
        vm.onPoll(listOf(proc(2))) // pid 1 gone
        val detail = vm.state.value.detail
        assertTrue(detail is DetailState.Ended)
        assertEquals(1L, (detail as DetailState.Ended).lastKnown.pid)
    }

    @Test
    fun `empty poll sets empty flag and zero counts`() {
        val vm = LiveViewModel()
        vm.onPoll(emptyList())
        val s = vm.state.value
        assertTrue(s.isEmpty)
        assertEquals(0, s.summary.activeProcessCount)
        assertEquals(0L, s.summary.totalRssMb)
    }

    @Test
    fun `process with unreadable cwd is permission-degraded`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1, cwd = null, project = null)))
        val s = vm.state.value
        assertTrue(s.isPermissionDegraded(s.processes.single()))
    }
}
