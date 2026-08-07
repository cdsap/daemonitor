package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryGraphModelTest {

    @Test
    fun `rows preserve process identity rss and heap limit`() {
        val rows = MemoryGraphModel.fromProcesses(
            listOf(
                process(
                    pid = 11,
                    type = ProcessType.GRADLE_DAEMON,
                    projectPath = "/Users/dev/app",
                    rssMemoryMb = 1024,
                    maxHeapMb = 4096,
                ),
            ),
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(11L, row.pid)
        assertEquals("Gradle daemon", row.title)
        assertEquals("app · PID 11", row.subtitle)
        assertEquals(1024L, row.rssMemoryMb)
        assertEquals(4096L, row.heapLimitMb)
    }

    @Test
    fun `missing heap limit is unavailable instead of zero`() {
        val row = MemoryGraphModel.fromProcesses(
            listOf(process(pid = 12, rssMemoryMb = 180, maxHeapMb = null)),
        ).single()

        assertEquals(180L, row.rssMemoryMb)
        assertNull(row.heapLimitMb)
        assertNull(row.heapFraction)
    }

    @Test
    fun `fractions scale against the largest rss or heap value`() {
        val rows = MemoryGraphModel.fromProcesses(
            listOf(
                process(pid = 1, rssMemoryMb = 512, maxHeapMb = 4096),
                process(pid = 2, rssMemoryMb = 8192, maxHeapMb = null),
            ),
        )

        assertEquals(0.0625f, rows[0].rssFraction)
        assertEquals(0.5f, rows[0].heapFraction)
        assertEquals(1.0f, rows[1].rssFraction)
        assertNull(rows[1].heapFraction)
    }

    @Test
    fun `empty process list returns empty graph rows`() {
        assertEquals(emptyList(), MemoryGraphModel.fromProcesses(emptyList()))
    }

    private fun process(
        pid: Long,
        type: ProcessType = ProcessType.GRADLE_WRAPPER,
        projectPath: String? = "/Users/dev/project",
        rssMemoryMb: Long,
        maxHeapMb: Long?,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = type,
        commandLine = "java org.gradle.wrapper.GradleWrapperMain build",
        workingDirectory = projectPath,
        projectPath = projectPath,
        cpuPercent = 10.0,
        rssMemoryMb = rssMemoryMb,
        maxHeapMb = maxHeapMb,
        minHeapMb = null,
        gc = "G1",
        startTimeMs = 1_700_000_000_000,
        status = "RUNNING",
    )
}
