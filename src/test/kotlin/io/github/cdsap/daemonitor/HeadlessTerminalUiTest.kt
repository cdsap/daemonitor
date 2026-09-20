package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HeadlessTerminalUiTest {
    @Test
    fun `renderer shows summary and processes sorted by memory`() {
        val output = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(
                processes = listOf(
                    process(pid = 10, rssMb = 256, heapLimitMb = 512, project = "small"),
                    process(pid = 11, rssMb = 1_024, heapLimitMb = 2_048, project = "large"),
                ),
                daemonLogs = emptyList(),
                buildsChanged = false,
            ),
            updatedAtMs = 1_000L,
        )

        assertTrue(output.contains("DAEMONITOR — HEADLESS"))
        assertTrue(output.contains("2 processes"))
        assertTrue(output.contains("1280 MB RSS"))
        assertTrue(output.contains("1024 MB"))
        assertTrue(output.contains("HEAP LIMIT"))
        assertTrue(output.contains("HEAP USED"))
        assertTrue(output.contains("HEAP CMT"))
        assertTrue(output.contains("2048 MB"))
        assertTrue(output.contains("n/a")) // missing live heap stays unavailable, not zero
        assertTrue(output.indexOf("large") < output.indexOf("small"))
        assertTrue(output.contains("Press q (or q + Enter) to quit · refreshes every 2 seconds"))
    }

    @Test
    fun `renderer footer labels the active poll interval with pluralization`() {
        val empty = WatcherRuntime.PollResult(emptyList(), emptyList(), false)

        val defaultFooter = HeadlessTerminalRenderer.render(
            result = empty,
            updatedAtMs = 1_000L,
            pollInterval = MonitoringConfig.DEFAULT.pollInterval,
        )
        val oneSecond = HeadlessTerminalRenderer.render(
            result = empty,
            updatedAtMs = 1_000L,
            pollInterval = 1.seconds,
        )
        val fiveSeconds = HeadlessTerminalRenderer.render(
            result = empty,
            updatedAtMs = 1_000L,
            pollInterval = 5.seconds,
        )

        assertTrue(defaultFooter.contains("refreshes every 2 seconds"))
        assertTrue(oneSecond.contains("refreshes every 1 second"))
        assertFalse(oneSecond.contains("refreshes every 1 seconds"))
        assertTrue(fiveSeconds.contains("refreshes every 5 seconds"))
    }

    @Test
    fun `renderer shows sampling for first-poll cpu and zero after sample`() {
        val sampling = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(
                processes = listOf(process(pid = 10, rssMb = 256, heapLimitMb = 512, project = "app", cpu = null)),
                daemonLogs = emptyList(),
                buildsChanged = false,
            ),
            updatedAtMs = 1_000L,
        )
        val idle = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(
                processes = listOf(process(pid = 10, rssMb = 256, heapLimitMb = 512, project = "app", cpu = 0.0)),
                daemonLogs = emptyList(),
                buildsChanged = false,
            ),
            updatedAtMs = 3_000L,
        )

        assertTrue(sampling.contains("…"), sampling)
        assertFalse(sampling.contains("  0%"), sampling)
        assertTrue(idle.contains("  0%"), idle)
        assertFalse(idle.contains("…"), idle)
    }

    @Test
    fun `renderer reports an empty monitor and poll errors`() {
        val output = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(emptyList(), emptyList(), false),
            updatedAtMs = 1_000L,
            error = "permission denied",
        )

        assertTrue(output.contains("No Gradle-related processes are running."))
        assertTrue(output.contains("Last poll failed: permission denied"))
    }

    @Test
    fun `interactive frames clear the screen before rendering`() {
        val output = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(emptyList(), emptyList(), false),
            updatedAtMs = 1_000L,
            clearScreen = true,
        )

        assertTrue(output.startsWith("\u001B[2J\u001B[H"))
    }

    @Test
    fun `renderer adds ansi colors only when enabled`() {
        val result = WatcherRuntime.PollResult(
            processes = listOf(process(pid = 10, rssMb = 256, heapLimitMb = 512, project = "small")),
            daemonLogs = emptyList(),
            buildsChanged = false,
        )

        val plain = HeadlessTerminalRenderer.render(result, updatedAtMs = 1_000L)
        val colored = HeadlessTerminalRenderer.render(result, updatedAtMs = 1_000L, colorEnabled = true)

        assertFalse(plain.contains("\u001B["))
        assertTrue(colored.contains("\u001B["))
    }

    @Test
    fun `input adapter quits on q without blocking`() {
        val output = ByteArrayOutputStream()
        val terminal = HeadlessTerminalUi(
            output = PrintStream(output),
            input = ByteArrayInputStream("xq".toByteArray()),
            clearScreen = false,
        )

        assertTrue(terminal.shouldQuit())
        assertFalse(terminal.shouldQuit())
    }

    private fun process(
        pid: Long,
        rssMb: Long,
        heapLimitMb: Long? = null,
        project: String,
        cpu: Double? = 12.0,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = "GradleDaemon",
        workingDirectory = "/tmp/$project",
        projectPath = "/tmp/$project",
        cpuPercent = cpu,
        rssMemoryMb = rssMb,
        maxHeapMb = heapLimitMb,
        minHeapMb = null,
        gc = null,
        startTimeMs = 0L,
        status = "RUNNING",
    )
}
