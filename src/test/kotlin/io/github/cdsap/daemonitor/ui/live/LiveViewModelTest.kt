package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.application.DaemonLogTailResult
import io.github.cdsap.daemonitor.application.DaemonLog
import java.nio.file.Path
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveViewModelTest {
    @Test
    fun `context results are selection scoped and errors differ from empty host`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1), proc(2)))
        val first = vm.select(1, true)!!
        val second = vm.select(2, true)!!
        vm.onHost(first, ContextState.Error)
        assertEquals(ContextState.Loading, vm.state.value.host)
        val empty = io.github.cdsap.daemonitor.application.HostContext(null, null, null, null, null)
        vm.onHost(second, ContextState.Loaded(empty))
        assertEquals(ContextState.Loaded(empty), vm.state.value.host)
        vm.onCapabilities(second, ContextState.Error)
        assertEquals(ContextState.Error, vm.state.value.capabilities)
        vm.clearSelection()
        vm.onHost(second, ContextState.Error)
        assertEquals(ContextState.Unavailable, vm.state.value.host)
    }

    @Test
    fun `reused pid ends selected incarnation and rejects late context`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1)))
        val generation = vm.select(1, true)!!
        vm.onPoll(listOf(proc(1).copy(startTimeMs = 2)))
        assertTrue(vm.state.value.detail is DetailState.Ended)
        vm.onHost(generation, ContextState.Error)
        assertEquals(ContextState.Unavailable, vm.state.value.host)
    }


    @Test
    fun `daemon metadata follows selection and refreshed polls`() {
        val vm = LiveViewModel()
        val daemon = proc(1)
        val other = proc(2).copy(type = ProcessType.KOTLIN_DAEMON)
        val log = DaemonLog(1, "8.14.3", Path.of("/tmp/daemon-1.out.log"))
        val otherLog = DaemonLog(2, "9.0", Path.of("/tmp/daemon-2.out.log"))
        vm.onPoll(listOf(daemon, other), daemonLogs = listOf(log, otherLog))
        vm.select(1)
        assertEquals(log, vm.state.value.daemonLog)
        vm.onPoll(listOf(daemon, other), daemonLogs = emptyList())
        assertNull(vm.state.value.daemonLog)
        vm.onPoll(listOf(daemon, other), daemonLogs = listOf(log, otherLog))
        vm.select(2)
        assertNull(vm.state.value.daemonLog)
        vm.select(1)
        vm.onPoll(listOf(other), daemonLogs = emptyList())
        assertEquals(log, vm.state.value.daemonLog)
        vm.clearSelection()
        assertNull(vm.state.value.daemonLog)
    }

    private fun proc(
        pid: Long,
        rss: Long = 100,
        project: String? = "/p",
        cwd: String? = "/p",
        heap: Long? = 512,
        heapUsed: Long? = null,
    ) = GradleProcess(
        pid = pid, parentPid = 1, type = ProcessType.GRADLE_DAEMON,
        commandLine = "java GradleDaemon", workingDirectory = cwd, projectPath = project,
        cpuPercent = 10.0, rssMemoryMb = rss, maxHeapMb = heap, minHeapMb = null,
        gc = "G1", startTimeMs = 1, status = "RUNNING",
        liveHeap = heapUsed?.let {
            io.github.cdsap.daemonitor.domain.model.LiveJvmHeap(
                usedMb = it,
                committedMb = it + 32,
                maxMb = heap,
                sampledAtMs = 1,
                available = true,
            )
        },
    )

    @Test
    fun `recent builds states reject stale selections and reused pids`() {
        val vm = LiveViewModel()
        val first = proc(1)
        vm.onPoll(listOf(first, proc(2)))
        vm.select(1)
        val token = vm.selectionToken()
        assertEquals(RecentBuildsState.Loading, vm.state.value.recentBuilds)
        vm.onRecentBuilds(token, first, emptyList())
        assertEquals(RecentBuildsState.Loaded(emptyList()), vm.state.value.recentBuilds)
        vm.select(2)
        vm.onRecentBuilds(token, first, emptyList())
        assertEquals(RecentBuildsState.Loading, vm.state.value.recentBuilds)
        vm.onRecentBuilds(vm.selectionToken(), proc(2), null)
        assertEquals(RecentBuildsState.Unavailable, vm.state.value.recentBuilds)
        vm.select(1)
        vm.onPoll(listOf(first.copy(startTimeMs = 2)))
        assertTrue(vm.state.value.detail is DetailState.Ended)
        assertEquals(RecentBuildsState.Unavailable, vm.state.value.recentBuilds)
    }

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
    fun `selection clears the previous tail until the selected tail arrives`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1), proc(2)), DaemonLogTailResult.Lines(listOf("pid 1")))
        vm.select(1)
        vm.onTail(1, DaemonLogTailResult.Lines(listOf("pid 1")))

        vm.select(2)

        assertEquals(emptyList(), vm.state.value.tail)
        assertEquals(LogTailState.Loading, vm.state.value.tailState)
    }

    @Test
    fun `tail failure is visible and stale result cannot overwrite a new selection`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1), proc(2)))
        vm.select(1)
        vm.onTail(1, DaemonLogTailResult.Error("IOException"))
        assertEquals(LogTailState.Error("IOException"), vm.state.value.tailState)

        vm.select(2)
        vm.onTail(1, DaemonLogTailResult.Lines(listOf("stale")))

        assertEquals(LogTailState.Loading, vm.state.value.tailState)
        assertEquals(emptyList(), vm.state.value.tail)
    }

    @Test
    fun `clearing selection resets the log panel`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1)))
        vm.select(1)
        vm.onTail(1, DaemonLogTailResult.Lines(listOf("line")))

        vm.clearSelection()

        assertEquals(DetailState.NoSelection, vm.state.value.detail)
        assertEquals(LogTailState.NoSelection, vm.state.value.tailState)
        assertEquals(emptyList(), vm.state.value.tail)
    }

    @Test
    fun `missing and empty tails become an explicit empty state`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1)))
        vm.select(1)
        vm.onTail(1, DaemonLogTailResult.NoLog)
        assertEquals(LogTailState.NoLog, vm.state.value.tailState)

        vm.select(1)
        vm.onTail(1, DaemonLogTailResult.Lines(emptyList()))
        assertEquals(LogTailState.NoLog, vm.state.value.tailState)
        assertEquals(emptyList(), vm.state.value.tail)
    }

    @Test
    fun `process ending during tail load does not leave the panel stuck loading`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1), proc(2)))
        vm.select(1)
        assertEquals(LogTailState.Loading, vm.state.value.tailState)

        vm.onPoll(listOf(proc(2)))

        assertTrue(vm.state.value.detail is DetailState.Ended)
        assertEquals(LogTailState.NoLog, vm.state.value.tailState)
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

    @Test
    fun `poll failure records a safe diagnostic and timestamp without discarding stale data`() {
        val vm = LiveViewModel()
        vm.onPoll(listOf(proc(1)))

        vm.onPollFailure(failedAtMs = 1234, errorType = "IllegalStateException")

        val state = vm.state.value
        assertEquals(listOf(1L), state.processes.map { it.pid })
        assertEquals(1234, state.pollError?.failedAtMs)
        assertEquals("IllegalStateException", state.pollError?.errorType)
    }

    @Test
    fun `successful poll clears the latest poll failure`() {
        val vm = LiveViewModel()
        vm.onPollFailure(failedAtMs = 1234, errorType = "IOException")

        vm.onPoll(emptyList())

        assertNull(vm.state.value.pollError)
    }

    @Test
    fun `poll appends rss timeline samples and respects capacity`() {
        var now = 1_000L
        val vm = LiveViewModel(clockMs = { now }, timelineCapacity = 3)

        vm.onPoll(listOf(proc(1, rss = 100)))
        now = 2_000L
        vm.onPoll(listOf(proc(1, rss = 120), proc(2, rss = 80)))
        now = 3_000L
        vm.onPoll(listOf(proc(1, rss = 140)))
        now = 4_000L
        vm.onPoll(listOf(proc(1, rss = 160)))

        val timeline = vm.state.value.rssTimeline
        assertEquals(3, timeline.size)
        assertEquals(2_000L, timeline.first().atMs)
        assertEquals(200L, timeline.first().totalRssMb)
        assertEquals(mapOf(1L to 120L, 2L to 80L), timeline.first().byPid)
        assertEquals(160L, timeline.last().totalRssMb)
    }

    @Test
    fun `poll samples configured heap limit and live used alongside rss`() {
        val vm = LiveViewModel(clockMs = { 5_000L })

        vm.onPoll(
            listOf(
                proc(1, rss = 100, heap = 4096, heapUsed = 220),
                proc(2, rss = 80, heap = null, heapUsed = null),
            ),
        )

        val sample = vm.state.value.rssTimeline.single()
        assertEquals(mapOf(1L to 100L, 2L to 80L), sample.byPid)
        assertEquals(mapOf(1L to 4096L), sample.heapLimitByPid)
        assertEquals(mapOf(1L to 220L), sample.heapUsedByPid)
    }
}
