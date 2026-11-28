package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.util.Locale

fun formatUptime(startMs: Long, nowMs: Long): String {
    val total = ((nowMs - startMs) / 1000).coerceAtLeast(0)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> String.format(Locale.ROOT, "${hours}h %02dm", minutes)
        minutes > 0 -> String.format(Locale.ROOT, "${minutes}m %02ds", seconds)
        else -> "${seconds}s"
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun ProcessType.displayLabel(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon"
    ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
    ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
    ProcessType.KOTLIN_TOOLCHAIN -> "Kotlin Toolchain"
    ProcessType.BAZEL_SERVER -> "Bazel server"
    ProcessType.BAZEL_TEST -> "Bazel test"
    ProcessType.BAZEL_WORKER -> "Bazel worker"
    ProcessType.TEST_WORKER -> "Gradle test worker"
    ProcessType.GRADLE_WORKER -> "Gradle worker"
    ProcessType.UNKNOWN_GRADLE_WORKER -> "Unknown Gradle worker"
    ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
}

fun displayProjectLabel(process: GradleProcess): String? {
    return projectBasename(process.projectPath) ?: projectBasename(process.workingDirectory)
}

/** Basename for either slash convention, including paths with trailing separators. */
fun projectBasename(path: String?): String? {
    val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val trimmed = raw.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return raw
    return trimmed.substringAfterLast('/').substringAfterLast('\\').ifEmpty { trimmed }
}
