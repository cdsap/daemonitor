package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.LiveMetricLabels
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.io.InputStream
import java.io.PrintStream
import java.time.Instant
import kotlin.time.Duration

/** Terminal presentation for the UI-independent headless monitoring runtime. */
class HeadlessTerminalUi(
    private val output: PrintStream,
    private val input: InputStream,
    private val clearScreen: Boolean,
    private val colorEnabled: Boolean = false,
    private val pollInterval: Duration = MonitoringConfig.DEFAULT.pollInterval,
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
                pollInterval = pollInterval,
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
        pollInterval: Duration = MonitoringConfig.DEFAULT.pollInterval,
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
            appendLine(ansi("TYPE             PID     RSS      HEAP USED  HEAP CMT   HEAP LIMIT  CPU   UPTIME   PROJECT", colorEnabled, BOLD))
            appendLine("────────────────────────────────────────────────────────────────────────────────────────")
            result.processes
                .sortedWith(compareByDescending<GradleProcess> { it.rssMemoryMb }.thenBy { it.pid })
                .forEach { process ->
                    val live = process.liveHeap?.takeIf { it.available }
                    appendLine(
                        "${process.type.displayName().padEnd(16)} " +
                            "${process.pid.toString().padStart(6)}  " +
                        "${ansi((process.rssMemoryMb.toString() + " MB").padStart(8), colorEnabled, YELLOW)}  " +
                            "${ansi(LiveMetricLabels.liveHeapUsedCompact(process.liveHeap).padStart(9), colorEnabled, GREEN)}  " +
                            "${ansi((live?.committedMb?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_COMPACT).padStart(9), colorEnabled, CYAN)}  " +
                            "${ansi((process.maxHeapMb?.let { "$it MB" } ?: LiveMetricLabels.HEAP_UNAVAILABLE_COMPACT).padStart(10), colorEnabled, MAGENTA)}  " +
                            "${cpuText(process.cpuPercent, colorEnabled)}  " +
                            "${uptime(process.startTimeMs, updatedAtMs).padStart(7)}  " +
                            projectName(process).take(32),
                    )
                }
        }

        appendLine()
        appendLine("Press q (or q + Enter) to quit · refreshes every ${formatPollInterval(pollInterval)}")
    }

    private fun formatPollInterval(pollInterval: Duration): String {
        val seconds = pollInterval.inWholeSeconds
        return if (seconds == 1L) "1 second" else "$seconds seconds"
    }

    private fun projectName(process: GradleProcess): String =
        process.projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: process.workingDirectory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "—"

    /**
     * Compact uptime aligned with the desktop live table: tick seconds under 1h so a 2s
     * poll interval visibly advances the UPTIME column instead of freezing on whole minutes.
     */
    private fun uptime(startTimeMs: Long, nowMs: Long): String {
        val total = ((nowMs - startTimeMs) / 1_000L).coerceAtLeast(0L)
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h %02dm".format(minutes)
            minutes > 0 -> "${minutes}m %02ds".format(seconds)
            else -> "${seconds}s"
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
        val text = LiveMetricLabels.cpuCli(cpuPercent)
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
    /** Clear the current screen before moving the cursor home for the next dashboard frame. */
    private const val CLEAR = "\u001B[2J\u001B[H"
    private const val BOLD = "1"
    private const val DIM = "2"
    private const val RED = "31"
    private const val GREEN = "32"
    private const val YELLOW = "33"
    private const val CYAN = "36"
    private const val MAGENTA = "35"
}
