package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build

/** A retained history item, either a traditional build or a cored observation session. */
sealed interface HistoryEntry {
    val startTimeMs: Long
}

data class BuildHistoryEntry(val build: Build) : HistoryEntry {
    override val startTimeMs: Long get() = build.startTimeMs
}

data class ObservationSessionHistoryEntry(
    val name: String,
    val hostId: String,
    val association: String,
    override val startTimeMs: Long,
    val endTimeMs: Long?,
    val processes: List<ObservationProcessSummary>,
    val buildCount: Long,
    val successCount: Long,
    val failureCount: Long,
) : HistoryEntry {
    val peakRssMb: Long? get() = processes.maxOfOrNull { it.peakRssMb }
}

data class ObservationProcessSummary(
    val hostId: String,
    val pid: Long,
    val startTimeMs: Long,
    val sampleCount: Long,
    val firstRssMb: Long,
    val peakRssMb: Long,
    val lastRssMb: Long,
    val deltaRssMb: Long,
    val association: String,
)
