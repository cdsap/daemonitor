package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.io.InputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Locale

/** Terminal presentation for the UI-independent headless monitoring runtime. */
internal class HeadlessTerminalUi(
    private val output: PrintStream,
    private val input: InputStream,
    private val clearScreen: Boolean,
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

internal object HeadlessTerminalRenderer {
    fun render(
        result: WatcherRuntime.PollResult,
        updatedAtMs: Long,
        error: String? = null,
        clearScreen: Boolean = false,
    ): String = buildString {
        if (clearScreen) append("\u001B[H\u001B[2J")
        appendLine("DAEMONITOR — HEADLESS")
        appendLine(
            "Updated ${Instant.ofEpochMilli(updatedAtMs)}  |  " +
                "${result.processes.size} processes  |  ${result.processes.sumOf { it.rssMemoryMb }} MB RSS  |  " +
                "${result.daemonLogs.size} daemon logs",
        )
        if (error != null) appendLine("Last poll failed: $error")
        appendLine()

        if (result.processes.isEmpty()) {
            appendLine("No Gradle-related processes are running.")
        } else {
            appendLine("TYPE             PID     RSS       CPU   UPTIME   PROJECT")
            appendLine("────────────────────────────────────────────────────────────")
            result.processes
                .sortedWith(compareByDescending<GradleProcess> { it.rssMemoryMb }.thenBy { it.pid })
                .forEach { process ->
                    appendLine(
                        "${process.type.displayName().padEnd(16)} " +
                            "${process.pid.toString().padStart(6)}  " +
                            "${(process.rssMemoryMb.toString() + " MB").padStart(8)}  " +
                            "${process.cpuPercent?.let { String.format(Locale.ROOT, "%3.0f%%", it) } ?: "  —"}  " +
                            "${uptime(process.startTimeMs, updatedAtMs).padStart(7)}  " +
                            projectName(process).take(32),
                    )
                }
        }

        appendLine()
        appendLine("Press q to quit · refreshes every 2 seconds")
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
}
