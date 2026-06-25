package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.Redactor
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.PriorSample
import io.github.cdsap.daemonitor.domain.model.ProcessInfo

/**
 * Pure, OS-independent construction of a [GradleProcess] snapshot from a [ProcessInfo] plus the
 * prior poll's CPU sample. Kept separate from the OSHI adapter so the classification, redaction,
 * and delta-CPU logic are unit-testable (U2).
 */
object ProcessSnapshotBuilder {

    /**
     * @return a redacted snapshot, or `null` if the process is not Gradle-related.
     * @param prior the previous sample for this process, or `null` on the first observation
     *   (in which case `cpuPercent` is `null` — never a lifetime average, KTD-4).
     */
    fun build(
        info: ProcessInfo,
        prior: PriorSample?,
        sampleWallClockMs: Long,
        logicalProcessorCount: Int,
    ): GradleProcess? {
        val type = GradleProcessClassifier.classify(info.commandLine) ?: return null
        val jvm = JvmArgParser.parse(info.commandLine)
        val cwd = info.workingDirectory.ifBlank { null }

        return GradleProcess(
            pid = info.pid,
            parentPid = info.parentPid,
            type = type,
            commandLine = Redactor.redactCommandLine(info.commandLine),
            workingDirectory = cwd,
            projectPath = cwd,
            cpuPercent = computeCpuPercent(info, prior, sampleWallClockMs, logicalProcessorCount),
            rssMemoryMb = info.rssBytes / (1024 * 1024),
            maxHeapMb = jvm.maxHeapMb,
            minHeapMb = jvm.minHeapMb,
            gc = jvm.gc,
            startTimeMs = info.startTimeMs,
            status = info.state,
            automated = InvocationFlags.isNonInteractive(info.commandLine),
        )
    }

    private fun computeCpuPercent(
        info: ProcessInfo,
        prior: PriorSample?,
        sampleWallClockMs: Long,
        logicalProcessorCount: Int,
    ): Double? {
        if (prior == null) return null
        val wallDelta = sampleWallClockMs - prior.wallClockMs
        if (wallDelta <= 0 || logicalProcessorCount <= 0) return null
        val cpuDelta = info.cpuTimeMs - prior.cpuTimeMs
        if (cpuDelta < 0) return null
        val pct = (cpuDelta.toDouble() / wallDelta) / logicalProcessorCount * 100.0
        return pct.coerceAtLeast(0.0)
    }
}
