package io.github.cdsap.daemonitor.gradle

/** Request fields accepted by cored's observation-session open endpoint. */
data class ObservationSessionOpenRequest(
    val sessionId: String? = null,
    val rootProjectPath: String,
    val buildId: String? = null,
    val ciJobId: String? = null,
    val hostId: String? = null,
    val gradleJvmPid: Int? = null,
    val gradleJvmStartTimeMs: Long? = null,
    val startedAtMs: Long? = null,
)

/** Process identity supplied to cored when an observation session closes. */
data class ObservationProcessHint(
    val pid: Int,
    val startTimeMs: Long? = null,
    val type: String? = null,
    val name: String? = null,
)

/** Request fields accepted by cored's observation-session close endpoint. */
data class ObservationSessionCloseRequest(
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val processHints: List<ObservationProcessHint> = emptyList(),
)

/** Phase-1 RSS summary returned by cored when an observation session closes. */
data class ObservationSummary(
    val schemaVersion: String,
    val sessionId: String,
    val rootProjectPath: String = "",
    val buildId: String = "",
    val startedAtMs: Long = 0,
    val endedAtMs: Long = 0,
    val outcome: String = "",
    val processes: List<ProcessRssSummary> = emptyList(),
    val totalPeakRssMb: Long = 0,
    val spikeNotes: List<String> = emptyList(),
)

data class ProcessRssSummary(
    val pid: Int,
    val type: String = "",
    val name: String = "",
    val association: String = "",
    val firstRssMb: Long? = null,
    val peakRssMb: Long? = null,
    val lastRssMb: Long? = null,
    val deltaRssMb: Long? = null,
    val sampleCount: Int = 0,
)
