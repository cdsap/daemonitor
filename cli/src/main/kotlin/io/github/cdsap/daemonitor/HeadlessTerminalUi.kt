package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.io.InputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Locale

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
}

object HeadlessTerminalRenderer {
    fun render(
        result: WatcherRuntime.PollResult,
        updatedAtMs: Long,
        error: String? = null,
        clearScreen: Boolean = false,
        colorEnabled: Boolean = false,
    ): String = buildString {
        if (clearScreen) append(CLEAR)
        appendLine(ansi("DAEMONITOR — HEADLESS", colorEnabled, BOLD, CYAN))
        appendLine(
            "Updated ${Instant.ofEpochMilli(updatedAtMs)}  |  " +
                "${result.processes.size} processes  |  " +
                ansi("${result.processes.sumOf { it.rssMemoryMb }} MB RSS", colorEnabled, YELLOW) + "  |  " +
                "${result.daemonLogs.size} daemon logs",
        )
        if (error != null) appendLine(ansi("Last poll failed: $error", colorEnabled, BOLD, RED))
        appendLine()

        if (result.processes.isEmpty()) {
            appendLine("No Gradle-related processes are running.")
        } else {
            appendLine(ansi(tableHeader(), colorEnabled, BOLD))
            appendLine("─".repeat(tableHeader().length))
            result.processes
                .sortedWith(compareByDescending<GradleProcess> { it.rssMemoryMb }.thenBy { it.pid })
                .forEach { process ->
                    appendLine(
                        tableRow(
                            process = process,
                            updatedAtMs = updatedAtMs,
                            colorEnabled = colorEnabled,
                        ),
                    )
                }
        }

        appendLine()
        appendLine("Press q (or q + Enter) to quit · refreshes every 2 seconds")
    }

    private fun projectName(process: GradleProcess): String =
        process.projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: process.workingDirectory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "—"

    private fun tableHeader(): String = listOf(
        "TYPE".padEnd(TYPE_WIDTH),
        "PID".padStart(PID_WIDTH),
        "RSS".padStart(RSS_WIDTH),
        "HEAP USED".padStart(HEAP_USED_WIDTH),
        "HEAP CMT".padStart(HEAP_COMMITTED_WIDTH),
        "HEAP LIMIT".padStart(HEAP_LIMIT_WIDTH),
        "CPU".padStart(CPU_WIDTH),
        "UPTIME".padStart(UPTIME_WIDTH),
        "PROJECT",
    ).joinToString(COLUMN_GAP)

    private fun tableRow(process: GradleProcess, updatedAtMs: Long, colorEnabled: Boolean): String = listOf(
        process.type.displayName().padEnd(TYPE_WIDTH),
        process.pid.toString().padStart(PID_WIDTH),
        ansi((process.rssMemoryMb.toString() + " MB").padStart(RSS_WIDTH), colorEnabled, YELLOW),
        ansi((process.heapUsedMb?.let { "$it MB" } ?: "—").padStart(HEAP_USED_WIDTH), colorEnabled, CYAN),
        ansi((process.heapCommittedMb?.let { "$it MB" } ?: "—").padStart(HEAP_COMMITTED_WIDTH), colorEnabled, CYAN),
        ansi((process.maxHeapMb?.let { "$it MB" } ?: "—").padStart(HEAP_LIMIT_WIDTH), colorEnabled, MAGENTA),
        cpuText(process.cpuPercent, colorEnabled).padStart(CPU_WIDTH),
        uptime(process.startTimeMs, updatedAtMs).padStart(UPTIME_WIDTH),
        projectName(process).take(32),
    ).joinToString(COLUMN_GAP)

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

    private const val TYPE_WIDTH = 16
    private const val PID_WIDTH = 6
    private const val RSS_WIDTH = 8
    private const val HEAP_USED_WIDTH = 9
    private const val HEAP_COMMITTED_WIDTH = 10
    private const val HEAP_LIMIT_WIDTH = 10
    private const val CPU_WIDTH = 4
    private const val UPTIME_WIDTH = 7
    private const val COLUMN_GAP = "  "

    private const val ESC = "\u001B["
    private const val RESET = "\u001B[0m"
    private const val CLEAR = "\u001B[H\u001B[2J"
    private const val BOLD = "1"
    private const val DIM = "2"
    private const val RED = "31"
    private const val GREEN = "32"
    private const val YELLOW = "33"
    private const val CYAN = "36"
    private const val MAGENTA = "35"
}
