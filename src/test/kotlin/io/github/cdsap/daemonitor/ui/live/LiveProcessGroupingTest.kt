package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveProcessGroupingTest {

    private fun process(
        pid: Long,
        parentPid: Long,
        type: ProcessType = ProcessType.GRADLE_DAEMON,
        rss: Long = 100,
        cpu: Double? = 10.0,
    ) = GradleProcess(
        pid = pid,
        parentPid = parentPid,
        type = type,
        commandLine = "java $type",
        workingDirectory = "/project",
        projectPath = "/project",
        cpuPercent = cpu,
        rssMemoryMb = rss,
        maxHeapMb = null,
        minHeapMb = null,
        gc = null,
        startTimeMs = pid,
        status = "RUNNING",
    )

    @Test
    fun `grouped rows aggregate daemon resources and retain child targets`() {
        val daemon = process(pid = 100, parentPid = 1, rss = 1_000, cpu = 12.0)
        val worker = process(pid = 200, parentPid = daemon.pid, type = ProcessType.GRADLE_WORKER, rss = 250, cpu = 7.5)
        val executor = process(pid = 300, parentPid = worker.pid, type = ProcessType.TEST_WORKER, rss = 125, cpu = null)
        val unrelated = process(pid = 400, parentPid = 1, type = ProcessType.KOTLIN_DAEMON, rss = 50)

        val rows = groupedLiveProcessRows(listOf(daemon, worker, executor, unrelated))

        assertEquals(listOf(100L, 200L, 300L, 400L), rows.map { it.targetPid })
        assertEquals(listOf(0, 1, 2, 0), rows.map { it.depth })
        assertEquals(true, rows.first().isGroupRoot)
        assertEquals(2, rows.first().groupChildCount)
        assertEquals(1_375L, rows.first().process.rssMemoryMb)
        assertEquals(19.5, rows.first().process.cpuPercent)
        assertEquals(daemon.pid, rows.first().targetPid)
        assertEquals(worker.pid, rows[1].targetPid)
        assertEquals(executor.pid, rows[2].targetPid)
    }

    @Test
    fun `grouped rows treat bazel server as hierarchy root`() {
        val bazel = process(pid = 500, parentPid = 1, type = ProcessType.BAZEL_SERVER, rss = 800, cpu = 5.0)
        val child = process(pid = 501, parentPid = bazel.pid, type = ProcessType.JAVA_GRADLE_RELATED, rss = 200, cpu = 1.0)

        val rows = groupedLiveProcessRows(listOf(bazel, child))

        assertEquals(2, rows.size)
        assertEquals(true, rows.first().isGroupRoot)
        assertEquals(ProcessType.BAZEL_SERVER, rows.first().process.type)
        assertEquals(1_000L, rows.first().process.rssMemoryMb)
        assertEquals(bazel.pid, rows.first().targetPid)
        assertEquals(child.pid, rows[1].targetPid)
    }

    @Test
    fun `grouped rows leave leaves unchanged and keep missing cpu unavailable`() {
        val leaf = process(pid = 10, parentPid = 1, type = ProcessType.GRADLE_WORKER, rss = 42, cpu = null)

        val rows = groupedLiveProcessRows(listOf(leaf))

        assertEquals(1, rows.size)
        assertEquals(leaf, rows.single().process)
        assertEquals(null, rows.single().process.cpuPercent)
        assertEquals(false, rows.single().isGroupRoot)
    }
}
