package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertTrue(output.contains("2048 MB"))
        assertTrue(output.indexOf("large") < output.indexOf("small"))
        assertTrue(output.contains("Press q (or q + Enter) to quit"))
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

    private fun process(pid: Long, rssMb: Long, heapLimitMb: Long? = null, project: String) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = "GradleDaemon",
        workingDirectory = "/tmp/$project",
        projectPath = "/tmp/$project",
        cpuPercent = 12.0,
        rssMemoryMb = rssMb,
        maxHeapMb = heapLimitMb,
        minHeapMb = null,
        gc = null,
        startTimeMs = 0L,
        status = "RUNNING",
    )
}
