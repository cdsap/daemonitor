package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlinx.coroutines.delay
import java.io.InputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Terminal presentation for the UI-independent headless monitoring runtime. */
class HeadlessTerminalUi(
    private val output: PrintStream,
    private val input: InputStream,
    private val clearScreen: Boolean,
    private val colorEnabled: Boolean = false,
) {
    fun render(
        result: WatcherRuntime.PollResult,
        updatedAtMs: Long,
        error: String? = null,
    ) {
        output.print(
            HeadlessTerminalRenderer.render(
                result = result,
                updatedAtMs = updatedAtMs,
                error = error,
                clearScreen = clearScreen,
                colorEnabled = colorEnabled,
            ),
        )
        output.flush()
    }

    /** Reads available input without blocking the monitoring loop. */
    fun shouldQuit(): Boolean = runCatching {
        var quit = false
        while (input.available() > 0) {
            val next = input.read()
            if (next == 'q'.code || next == 'Q'.code) quit = true
        }
        quit
    }.getOrDefault(false)

    /**
     * Waits for the next poll interval while watching for quit input.
     * Returns false when the session should stop without another refresh.
     */
    suspend fun waitForNextPoll(interval: Duration): Boolean {
        if (shouldQuit()) return false
        val deadlineNs = System.nanoTime() + interval.inWholeNanoseconds
        while (System.nanoTime() < deadlineNs) {
            if (shouldQuit()) return false
            val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs == 0L) break
            delay(minOf(remainingMs, INPUT_POLL_CHUNK_MS).milliseconds)
        }
        return !shouldQuit()
    }

    private companion object {
        private const val INPUT_POLL_CHUNK_MS = 50L
    }
}

object HeadlessTerminalRenderer {
    fun render(
        result: WatcherRuntime.PollResult,
        updatedAtMs: Long,
        error: String? = null,
        clearScreen: Boolean = false,
        colorEnabled: Boolean = false,
    ): String = buildString {
        if (clearScreen) append(CURSOR_HOME)

        fun line(text: String = "") {
            append(text)
            if (clearScreen) append(CLEAR_EOL)
            append('\n')
        }

        line(ansi("DAEMONITOR — HEADLESS", colorEnabled, BOLD, CYAN))
        line(
            "Updated ${Instant.ofEpochMilli(updatedAtMs)}  |  " +
                "${result.processes.size} processes  |  " +
                ansi("${result.processes.sumOf { it.rssMemoryMb }} MB RSS", colorEnabled, YELLOW) + "  |  " +
                "${result.daemonLogs.size} daemon logs",
        )
        if (error != null) line(ansi("Last poll failed: $error", colorEnabled, BOLD, RED))
        line()

        if (result.processes.isEmpty()) {
            line("No Gradle-related processes are running.")
        } else {
            line(ansi("TYPE             PID     RSS       HEAP LIMIT   CPU   UPTIME   PROJECT", colorEnabled, BOLD))
            line("──────────────────────────────────────────────────────────────────────")
            result.processes
                .sortedWith(compareByDescending<GradleProcess> { it.rssMemoryMb }.thenBy { it.pid })
                .forEach { process ->
                    line(
                        "${process.type.displayName().padEnd(16)} " +
                            "${process.pid.toString().padStart(6)}  " +
                            "${ansi((process.rssMemoryMb.toString() + " MB").padStart(8), colorEnabled, YELLOW)}  " +
                            "${ansi((process.maxHeapMb?.let { "$it MB" } ?: "—").padStart(10), colorEnabled, MAGENTA)}  " +
                            "${cpuText(process.cpuPercent, colorEnabled)}  " +
                            "${uptime(process.startTimeMs, updatedAtMs).padStart(7)}  " +
                            projectName(process).take(32),
                    )
                }
        }

        line()
        line("Press q (or q + Enter) to quit · refreshes every 2 seconds")
        if (clearScreen) append(ERASE_DOWN)
    }

    private fun projectName(process: GradleProcess): String =
        process.projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: process.workingDirectory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "—"

    private fun uptime(startTimeMs: Long, nowMs: Long): String {
        val seconds = ((nowMs - startTimeMs).coerceAtLeast(0L)) / 1_000L
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h ${(seconds / 60) % 60}m"
            else -> "${seconds / 86_400}d ${(seconds / 3_600) % 24}h"
        }
    }

    private fun ProcessType.displayName(): String = when (this) {
        ProcessType.GRADLE_DAEMON -> "Gradle daemon"
        ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
        ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
        ProcessType.TEST_WORKER -> "Test worker"
        ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
    }

    private fun cpuText(cpuPercent: Double?, colorEnabled: Boolean): String {
        val text = cpuPercent?.let { String.format(Locale.ROOT, "%3.0f%%", it) } ?: "  —"
        val color = when {
            cpuPercent == null -> DIM
            cpuPercent >= 80.0 -> RED
            cpuPercent >= 40.0 -> YELLOW
            else -> GREEN
        }
        return ansi(text, colorEnabled, color)
    }

    private fun ansi(text: String, enabled: Boolean, vararg codes: String): String =
        if (enabled) codes.joinToString(prefix = ESC, postfix = "m") + text + RESET else text

    private const val ESC = "\u001B["
    private const val RESET = "\u001B[0m"
    /** Move to the top-left without pushing prior frames into scrollback. */
    private const val CURSOR_HOME = "\u001B[H"
    /** Clear from the cursor to the end of the current line. */
    private const val CLEAR_EOL = "\u001B[K"
    /** Clear from the cursor to the end of the screen (drops stale dynamic rows). */
    private const val ERASE_DOWN = "\u001B[J"
    private const val BOLD = "1"
    private const val DIM = "2"
    private const val RED = "31"
    private const val GREEN = "32"
    private const val YELLOW = "33"
    private const val CYAN = "36"
    private const val MAGENTA = "35"
}
