package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.PriorSample
import io.github.cdsap.daemonitor.domain.model.ProcessInfo
import oshi.SystemInfo
import oshi.software.os.OSProcess

/**
 * OSHI-backed process collector (U2). Enumerates the current user's processes each poll, adapts
 * each `OSProcess` to [ProcessInfo], and delegates snapshot construction to
 * [ProcessSnapshotBuilder]. Scoped to the effective UID of the watcher process (KTD-6): cross-user
 * command lines / cwd are unreadable on macOS anyway, and limiting scope avoids becoming a
 * cross-user credential-reading target.
 */
class ProcessCollector(
    private val systemInfo: SystemInfo = SystemInfo(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ProcessSource {
    private val os = systemInfo.operatingSystem
    private val logicalProcessors = systemInfo.hardware.processor.logicalProcessorCount
    private val selfPid: Int = os.processId
    private val selfUid: String? = runCatching { os.getProcess(selfPid).userID }.getOrNull()

    /** Prior CPU sample per process, keyed by (pid, startTime) to survive PID reuse (KTD-4). */
    private val priorSamples = mutableMapOf<ProcessKey, PriorSample>()

    override fun currentProcesses(): List<GradleProcess> = poll()

    fun poll(): List<GradleProcess> {
        val now = clock()
        val seen = mutableSetOf<ProcessKey>()
        val result = mutableListOf<GradleProcess>()

        for (p in os.processes) {
            if (p.processID == selfPid) continue // don't monitor ourselves
            if (selfUid != null && p.userID != selfUid) continue
            val info = p.toProcessInfo()
            val key = ProcessKey(info.pid, info.startTimeMs)
            seen += key
            val prior = priorSamples[key]
            val snapshot = ProcessSnapshotBuilder.build(info, prior, now, logicalProcessors)
            priorSamples[key] = PriorSample(info.cpuTimeMs, now)
            if (snapshot != null) result += snapshot
        }

        // Drop prior samples for processes that have disappeared.
        priorSamples.keys.retainAll(seen)
        return result
    }

    private data class ProcessKey(val pid: Long, val startTimeMs: Long)

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
