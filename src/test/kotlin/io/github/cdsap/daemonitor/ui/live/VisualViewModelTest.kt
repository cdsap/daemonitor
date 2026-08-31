package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.application.ProcessSampleRepository
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.persistence.ProcessSample
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualViewModelTest {

    @Test
    fun `selecting one hour loads expected persisted range`() = runTest {
        val repo = RecordingSamples(
            listOf(sample(timestamp = 9_000, pid = 10, rss = 300, heap = 1024)),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = VisualViewModel(repo, TestScope(dispatcher), clockMs = { 10_000 }, ioDispatcher = dispatcher)
        vm.onLiveState(liveState(listOf(process(pid = 10, rss = 200, heap = 1024))))

        vm.selectRange(VisualRange.ONE_HOUR)
        advanceUntilIdle()

        assertEquals(10_000 - 60 * 60 * 1000L, repo.lastFromMs)
        assertEquals(10_000L, repo.lastToMs)
        assertEquals(VisualRange.ONE_HOUR, vm.state.value.selectedRange)
        assertEquals(10L, vm.state.value.selectedPid)
        assertEquals(300L, vm.state.value.selectedSummary?.currentRssMb)
    }

    @Test
    fun `switching back to live uses in memory samples`() = runTest {
        val repo = RecordingSamples(emptyList())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = VisualViewModel(repo, TestScope(dispatcher), clockMs = { 10_000 }, ioDispatcher = dispatcher)
        vm.onLiveState(liveState(listOf(process(pid = 10, rss = 200, heap = 1024))))

        vm.selectRange(VisualRange.FIFTEEN_MINUTES)
        advanceUntilIdle()
        vm.selectRange(VisualRange.LIVE)

        assertEquals(VisualRange.LIVE, vm.state.value.selectedRange)
        assertEquals(200L, vm.state.value.currentTotalRssMb)
        assertEquals(10L, vm.state.value.selectedPid)
        assertEquals(1, repo.callCount)
    }

    @Test
    fun `selection survives historic range when pid exists`() = runTest {
        val repo = RecordingSamples(
            listOf(
                sample(timestamp = 8_000, pid = 10, rss = 200, heap = 1024),
                sample(timestamp = 9_000, pid = 11, rss = 900, heap = 2048),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = VisualViewModel(repo, TestScope(dispatcher), clockMs = { 10_000 }, ioDispatcher = dispatcher)
        vm.onLiveState(liveState(listOf(process(pid = 10, rss = 200, heap = 1024))))
        vm.selectProcess(10)

        vm.selectRange(VisualRange.FIFTEEN_MINUTES)
        advanceUntilIdle()

        assertEquals(10L, vm.state.value.selectedPid)
    }

    @Test
    fun `empty historic range publishes empty state`() = runTest {
        val repo = RecordingSamples(emptyList())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = VisualViewModel(repo, TestScope(dispatcher), clockMs = { 10_000 }, ioDispatcher = dispatcher)
        vm.onLiveState(liveState(listOf(process(pid = 10, rss = 200, heap = 1024))))

        vm.selectRange(VisualRange.FIFTEEN_MINUTES)
        advanceUntilIdle()

        assertTrue(vm.state.value.isEmpty)
        assertEquals("No samples in this range", vm.state.value.statusText)
    }

    @Test
    fun `empty live state keeps explicit no processes copy`() = runTest {
        val repo = RecordingSamples(emptyList())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = VisualViewModel(repo, TestScope(dispatcher), clockMs = { 10_000 }, ioDispatcher = dispatcher)

        vm.onLiveState(
            LiveUiState(
                processes = emptyList(),
                isLoading = false,
                isEmpty = true,
            ),
        )

        assertTrue(vm.state.value.isEmpty)
        assertEquals("No Gradle processes are running right now.", vm.state.value.statusText)
    }

    private class RecordingSamples(private val rows: List<ProcessSample>) : ProcessSampleRepository {
        var lastFromMs: Long? = null
        var lastToMs: Long? = null
        var callCount = 0

        override fun save(sample: GradleProcess, timestampMs: Long) = Unit

        override fun samplesInRange(fromMs: Long, toMs: Long): List<ProcessSample> {
            callCount += 1
            lastFromMs = fromMs
            lastToMs = toMs
            return rows
        }
    }

    private fun liveState(processes: List<GradleProcess>): LiveUiState {
        val total = processes.sumOf { it.rssMemoryMb }
        return LiveUiState(
            processes = processes,
            summary = LiveSummary(
                activeProcessCount = processes.size,
                totalRssMb = total,
                highestMemoryPid = processes.maxByOrNull { it.rssMemoryMb }?.pid,
                activeProjectCount = 1,
            ),
            isLoading = false,
            isEmpty = processes.isEmpty(),
            rssTimeline = listOf(
                RssTimelineSample(
                    atMs = 10_000,
                    totalRssMb = total,
                    byPid = processes.associate { it.pid to it.rssMemoryMb },
                    heapByPid = processes.mapNotNull { process ->
                        process.maxHeapMb?.let { process.pid to it }
                    }.toMap(),
                ),
            ),
        )
    }

    private fun process(pid: Long, rss: Long, heap: Long?) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = "java GradleDaemon",
        workingDirectory = "/repo",
        projectPath = "/repo",
        cpuPercent = 1.0,
        rssMemoryMb = rss,
        maxHeapMb = heap,
        minHeapMb = null,
        gc = null,
        startTimeMs = 1,
        status = "RUNNING",
    )

    private fun sample(timestamp: Long, pid: Long, rss: Long, heap: Long?) = ProcessSample(
        timestampMs = timestamp,
        pid = pid,
        parentPid = 1,
        processType = ProcessType.GRADLE_DAEMON,
        commandLine = "java GradleDaemon",
        workingDirectory = "/repo",
        projectPath = "/repo",
        cpuPercent = 1.0,
        rssMemoryMb = rss,
        maxHeapMb = heap,
        status = "RUNNING",
    )
}
