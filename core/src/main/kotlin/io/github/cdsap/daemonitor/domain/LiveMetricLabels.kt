package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.util.Locale

/**
 * Shared Live/CLI labels so unavailable, zero, and not-yet-sampled metrics stay distinct
 * (issue #186). CPU needs a prior poll for a delta sample; live heap is only probed for
 * Gradle/Kotlin daemons.
 */
object LiveMetricLabels {
    /** Compact table/CLI CPU when no prior poll exists yet. */
    const val CPU_SAMPLING_COMPACT = "…"

    /** Detail-panel CPU when no prior poll exists yet. */
    const val CPU_SAMPLING_DETAIL = "sampling…"

    /** Compact table/CLI live-heap when missing. Distinct from [CPU_SAMPLING_COMPACT]. */
    const val HEAP_UNAVAILABLE_COMPACT = "n/a"

    const val HEAP_UNAVAILABLE_DETAIL = "unavailable"

    fun cpuCompact(cpuPercent: Double?): String =
        cpuPercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: CPU_SAMPLING_COMPACT

    /** Fixed-width CLI cell matching `%3.0f%%`. */
    fun cpuCli(cpuPercent: Double?): String =
        cpuPercent?.let { String.format(Locale.ROOT, "%3.0f%%", it) } ?: "  $CPU_SAMPLING_COMPACT"

    fun cpuDetail(cpuPercent: Double?): String =
        cpuPercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: CPU_SAMPLING_DETAIL

    fun liveHeapUsedCompact(liveHeap: LiveJvmHeap?): String =
        liveHeap?.takeIf { it.available }?.usedMb?.let { "$it MB" } ?: HEAP_UNAVAILABLE_COMPACT

    fun liveHeapUsedDetail(liveHeap: LiveJvmHeap?, type: ProcessType): String {
        liveHeap?.takeIf { it.available }?.usedMb?.let { return "$it MB" }
        return if (isLiveHeapProbedType(type)) {
            HEAP_UNAVAILABLE_DETAIL
        } else {
            "$HEAP_UNAVAILABLE_DETAIL (not probed for ${unprobedTypePhrase(type)})"
        }
    }

    fun isLiveHeapProbedType(type: ProcessType): Boolean = when (type) {
        ProcessType.GRADLE_DAEMON, ProcessType.KOTLIN_DAEMON -> true
        else -> false
    }

    private fun unprobedTypePhrase(type: ProcessType): String = when (type) {
        ProcessType.GRADLE_WRAPPER -> "wrappers"
        ProcessType.TEST_WORKER -> "test workers"
        ProcessType.JAVA_GRADLE_RELATED -> "non-daemon JVMs"
        ProcessType.GRADLE_DAEMON, ProcessType.KOTLIN_DAEMON -> "daemons"
    }
}
