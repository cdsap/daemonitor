package io.github.cdsap.daemonitor.domain.model

/** Classification of a Gradle-related process (U2). */
enum class ProcessType {
    GRADLE_DAEMON,
    GRADLE_WRAPPER,
    KOTLIN_DAEMON,
    TEST_WORKER,
    JAVA_GRADLE_RELATED,
}

/** JVM memory/GC flags parsed from a process command line (KTD-3). */
data class JvmArgs(
    val maxHeapMb: Long? = null,
    val minHeapMb: Long? = null,
    val gc: String? = null,
    val daemonFlags: List<String> = emptyList(),
)

/**
 * Live JVM heap snapshot from a diagnostic probe (Attach/JMX or Go `jcmd`/`jstat`), distinct
 * from OS RSS and the configured `-Xmx` limit on [GradleProcess.maxHeapMb].
 *
 * Missing values stay `null` — never coerced to zero — when [available] is false.
 */
data class LiveJvmHeap(
    val usedMb: Long? = null,
    val committedMb: Long? = null,
    val maxMb: Long? = null,
    val sampledAtMs: Long,
    val available: Boolean,
) {
    companion object {
        fun unavailable(sampledAtMs: Long): LiveJvmHeap = LiveJvmHeap(
            usedMb = null,
            committedMb = null,
            maxMb = null,
            sampledAtMs = sampledAtMs,
            available = false,
        )
    }
}

/**
 * Minimal, OSHI-independent view of one OS process. The OSHI adapter (ProcessCollector) maps
 * `oshi.software.os.OSProcess` onto this so the pure snapshot logic stays unit-testable.
 */
interface ProcessInfo {
    val pid: Long
    val parentPid: Long
    val name: String
    val commandLine: String
    /** Empty string when unreadable (e.g. cross-UID or SIP-restricted). */
    val workingDirectory: String
    val rssBytes: Long
    val startTimeMs: Long
    val state: String
    /** Effective UID owning the process; used to scope to the current user (KTD-6). */
    val userId: String
    /** Cumulative CPU time (kernel + user), in ms — basis for delta-CPU (KTD-4). */
    val cpuTimeMs: Long
}

/** Prior-poll CPU sample retained per process for delta computation (KTD-4). */
data class PriorSample(val cpuTimeMs: Long, val wallClockMs: Long)

/** One poll-time snapshot of a Gradle-related process. `commandLine` is already redacted (KTD-7). */
data class GradleProcess(
    val pid: Long,
    val parentPid: Long,
    val type: ProcessType,
    val commandLine: String,
    val workingDirectory: String?,
    val projectPath: String?,
    val cpuPercent: Double?,
    val rssMemoryMb: Long,
    val maxHeapMb: Long?,
    val minHeapMb: Long?,
    val gc: String?,
    val startTimeMs: Long,
    val status: String,
    /** True when the invocation carries an automation marker like `--non-interactive` (Gradle 9.6+). */
    val automated: Boolean = false,
    /**
     * Live heap from Attach/JMX or Go `jcmd`/`jstat` when available. Distinct from [rssMemoryMb]
     * (OS RSS) and [maxHeapMb] (configured `-Xmx`). Null means not reported; [LiveJvmHeap.available]
     * false means probed/typed but unavailable.
     */
    val liveHeap: LiveJvmHeap? = null,
    // Extended OS/JVM metrics supplied by daemonitor-cored. Null means unavailable on the
    // current platform or for the selected process; it is never a synthetic zero.
    val virtualMemoryMb: Long? = null,
    val swapMemoryMb: Long? = null,
    val threadCount: Long? = null,
    val readBytes: Long? = null,
    val writeBytes: Long? = null,
    val readOperations: Long? = null,
    val writeOperations: Long? = null,
    val minorPageFaults: Long? = null,
    val majorPageFaults: Long? = null,
    val voluntaryContextSwitches: Long? = null,
    val involuntaryContextSwitches: Long? = null,
    val openFileDescriptors: Long? = null,
    val metaspaceUsedMb: Long? = null,
    val metaspaceCommittedMb: Long? = null,
    val youngGcCount: Long? = null,
    val youngGcTimeMs: Long? = null,
    val oldGcCount: Long? = null,
    val oldGcTimeMs: Long? = null,
    val javaVersion: String? = null,
    val javaRuntimeVersion: String? = null,
    val javaVendor: String? = null,
    val javaVmName: String? = null,
    val javaVmVersion: String? = null,
    val osName: String? = null,
    val osArch: String? = null,
    val activeProcessorCount: Long? = null,
)
