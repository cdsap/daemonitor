package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.PriorSample
import io.github.cdsap.daemonitor.domain.model.ProcessInfo
import io.github.cdsap.daemonitor.domain.model.ProcessType
import oshi.SystemInfo
import oshi.software.os.OSProcess

/**
 * OSHI-backed process collector (U2). Enumerates the current user's processes each poll, adapts
 * each `OSProcess` to [ProcessInfo], and delegates snapshot construction to
 * [ProcessSnapshotBuilder]. Scoped to the effective UID of the watcher process (KTD-6): cross-user
 * command lines / cwd are unreadable on macOS anyway, and limiting scope avoids becoming a
 * cross-user credential-reading target.
 *
 * Implements [ProcessSource] so application polling depends on the port, not OSHI.
 */
class ProcessCollector(
    private val systemInfo: SystemInfo = SystemInfo(),
    private val clock: () -> Long = System::currentTimeMillis,
    heapProbe: JvmHeapProbe = AttachJvmHeapProbe(),
    heapRefreshIntervalMs: Long = JvmHeapUsageCollector.DEFAULT_REFRESH_INTERVAL_MS,
) : ProcessSource {
    private val os = systemInfo.operatingSystem
    private val logicalProcessors = systemInfo.hardware.processor.logicalProcessorCount
    private val selfPid: Int = os.processId
    private val selfUid: String? = runCatching { os.getProcess(selfPid).userID }.getOrNull()
    private val heapUsageCollector = JvmHeapUsageCollector(
        probe = heapProbe,
        clock = clock,
        refreshIntervalMs = heapRefreshIntervalMs,
    )

    /** Prior CPU sample per process, keyed by (pid, startTime) to survive PID reuse (KTD-4). */
    private val priorSamples = mutableMapOf<ProcessIdentity, PriorSample>()

    override fun currentProcesses(): List<GradleProcess> {
        val now = clock()
        val seen = mutableSetOf<ProcessIdentity>()
        val result = mutableListOf<GradleProcess>()

        for (p in os.processes) {
            if (p.processID == selfPid) continue // don't monitor ourselves
            if (selfUid != null && p.userID != selfUid) continue
            val info = p.toProcessInfo()
            val key = ProcessIdentity(info.pid, info.startTimeMs)
            seen += key
            val prior = priorSamples[key]
            val snapshot = ProcessSnapshotBuilder.build(info, prior, now, logicalProcessors)
            priorSamples[key] = PriorSample(info.cpuTimeMs, now)
            if (snapshot != null) {
                // Live heap is orthogonal to RSS / -Xmx; probe failures stay unavailable (not zero).
                // Scope Attach/JMX to daemon JVMs (issue #159); wrappers/workers stay unavailable.
                val liveHeap = when (snapshot.type) {
                    ProcessType.GRADLE_DAEMON, ProcessType.KOTLIN_DAEMON ->
                        heapUsageCollector.read(info.pid, info.startTimeMs, now)
                    else -> LiveJvmHeap.unavailable(now)
                }
                result += snapshot.copy(liveHeap = liveHeap)
            }
        }

        // Drop prior CPU samples and live-heap cache entries for processes that disappeared.
        priorSamples.keys.retainAll(seen)
        heapUsageCollector.retainOnly(seen)
        return result
    }

    /** Compatibility alias for [currentProcesses]. */
    fun poll(): List<GradleProcess> = currentProcesses()

    private fun OSProcess.toProcessInfo(): ProcessInfo = OshiProcessInfo(this)

    private class OshiProcessInfo(private val p: OSProcess) : ProcessInfo {
        override val pid: Long get() = p.processID.toLong()
        override val parentPid: Long get() = p.parentProcessID.toLong()
        override val name: String get() = p.name ?: ""
        override val commandLine: String get() = p.commandLine ?: ""
        override val workingDirectory: String get() = p.currentWorkingDirectory ?: ""
        override val rssBytes: Long get() = p.residentSetSize
        override val startTimeMs: Long get() = p.startTime
        override val state: String get() = p.state?.name ?: "UNKNOWN"
        override val userId: String get() = p.userID ?: ""
        override val cpuTimeMs: Long get() = p.kernelTime + p.userTime
    }
}
