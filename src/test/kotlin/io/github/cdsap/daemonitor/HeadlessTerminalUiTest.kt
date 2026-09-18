package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
    fun `interactive renderer homes cursor and clears stale rows without full screen clear`() {
        val tall = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(
                processes = listOf(
                    process(pid = 10, rssMb = 256, project = "one"),
                    process(pid = 11, rssMb = 512, project = "two"),
                ),
                daemonLogs = emptyList(),
                buildsChanged = false,
            ),
            updatedAtMs = 1_000L,
            clearScreen = true,
        )
        val short = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(emptyList(), emptyList(), false),
            updatedAtMs = 2_000L,
            error = "permission denied",
            clearScreen = true,
        )

        assertTrue(tall.startsWith("\u001B[H"))
        assertTrue(tall.contains("\u001B[K"))
        assertTrue(tall.endsWith("\u001B[J"))
        assertFalse(tall.contains("\u001B[2J"))

        assertTrue(short.startsWith("\u001B[H"))
        assertTrue(short.contains("\u001B[K"))
        assertTrue(short.endsWith("\u001B[J"))
        assertTrue(short.contains("No Gradle-related processes are running."))
        assertTrue(short.contains("Last poll failed: permission denied"))
        assertTrue(tall.lines().size > short.lines().size)
    }

    @Test
    fun `plain renderer stays append-only without clear sequences`() {
        val output = HeadlessTerminalRenderer.render(
            result = WatcherRuntime.PollResult(
                processes = listOf(process(pid = 10, rssMb = 256, project = "plain")),
                daemonLogs = emptyList(),
                buildsChanged = false,
            ),
            updatedAtMs = 1_000L,
            clearScreen = false,
            colorEnabled = false,
        )

        assertFalse(output.contains("\u001B[H"))
        assertFalse(output.contains("\u001B[J"))
        assertFalse(output.contains("\u001B[K"))
        assertFalse(output.contains("\u001B[2J"))
        assertTrue(output.startsWith("DAEMONITOR — HEADLESS"))
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

    @Test
    fun `input adapter quits on q followed by enter`() {
        val terminal = HeadlessTerminalUi(
            output = PrintStream(ByteArrayOutputStream()),
            input = ByteArrayInputStream("q\n".toByteArray()),
            clearScreen = false,
        )

        assertTrue(terminal.shouldQuit())
    }

    @Test
    fun `waitForNextPoll returns false promptly when quit is pending`() = runBlocking {
        val terminal = HeadlessTerminalUi(
            output = PrintStream(ByteArrayOutputStream()),
            input = ByteArrayInputStream("q".toByteArray()),
            clearScreen = false,
        )

        val startedAt = System.nanoTime()
        assertFalse(terminal.waitForNextPoll(2.seconds))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(elapsedMs < 500, "expected prompt quit, took ${elapsedMs}ms")
    }

    @Test
    fun `waitForNextPoll completes interval when quit is not pressed`() = runBlocking {
        val terminal = HeadlessTerminalUi(
            output = PrintStream(ByteArrayOutputStream()),
            input = ByteArrayInputStream(ByteArray(0)),
            clearScreen = false,
        )

        val startedAt = System.nanoTime()
        assertTrue(terminal.waitForNextPoll(120.milliseconds))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(elapsedMs >= 100, "expected to wait for interval, took ${elapsedMs}ms")
    }

    @Test
    fun `interactive ui flushes an in-place frame`() {
        val output = ByteArrayOutputStream()
        val terminal = HeadlessTerminalUi(
            output = PrintStream(output, true),
            input = ByteArrayInputStream(ByteArray(0)),
            clearScreen = true,
            colorEnabled = true,
        )

        terminal.render(
            result = WatcherRuntime.PollResult(emptyList(), emptyList(), false),
            updatedAtMs = 1_000L,
        )

        val frame = output.toString()
        assertTrue(frame.startsWith("\u001B[H"))
        assertTrue(frame.endsWith("\u001B[J"))
        assertTrue(frame.contains("\u001B[K"))
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
