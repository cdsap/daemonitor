package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

data class MemoryGraphRow(
    val pid: Long,
    val title: String,
    val subtitle: String,
    val rssMemoryMb: Long,
    val heapLimitMb: Long?,
    val rssFraction: Float,
    val heapFraction: Float?,
)

object MemoryGraphModel {
    fun fromProcesses(processes: List<GradleProcess>): List<MemoryGraphRow> {
        val maxValue = processes
            .flatMap { process -> listOfNotNull(process.rssMemoryMb, process.maxHeapMb) }
            .maxOrNull()
            ?.takeIf { it > 0 }
            ?: return processes.map { it.toMemoryGraphRow(scaleMb = 1) }

        return processes.map { it.toMemoryGraphRow(scaleMb = maxValue) }
    }

    private fun GradleProcess.toMemoryGraphRow(scaleMb: Long): MemoryGraphRow {
        val project = projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val subtitle = listOfNotNull(project, "PID $pid").joinToString(" · ")
        return MemoryGraphRow(
            pid = pid,
            title = type.displayLabel(),
            subtitle = subtitle,
            rssMemoryMb = rssMemoryMb,
            heapLimitMb = maxHeapMb,
            rssFraction = fraction(rssMemoryMb, scaleMb),
            heapFraction = maxHeapMb?.let { fraction(it, scaleMb) },
        )
    }

    private fun fraction(value: Long, scaleMb: Long): Float =
        (value.toFloat() / scaleMb.toFloat()).coerceIn(0f, 1f)
}

internal fun ProcessType.displayLabel(): String = when (this) {
    ProcessType.GRADLE_DAEMON -> "Gradle daemon"
    ProcessType.GRADLE_WRAPPER -> "Gradle wrapper"
    ProcessType.KOTLIN_DAEMON -> "Kotlin daemon"
    ProcessType.TEST_WORKER -> "Test worker"
    ProcessType.JAVA_GRADLE_RELATED -> "Java (Gradle)"
}
