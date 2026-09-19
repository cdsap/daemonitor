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
 *
 * Heap attachment runs only after classification retains a Gradle/Kotlin daemon snapshot
 * (issue #168 / #159): ordinary same-user processes are never probed.
 */
class ProcessCollector internal constructor(
    private val clock: () -> Long,
    private val heapProbe: JvmHeapProbe,
    private val logicalProcessors: Int,
    private val enumerateProcesses: () -> List<ProcessInfo>,
) : ProcessSource {
    constructor(
        systemInfo: SystemInfo = SystemInfo(),
        clock: () -> Long = System::currentTimeMillis,
        heapProbe: JvmHeapProbe = AttachJvmHeapProbe(),
    ) : this(
        clock = clock,
        heapProbe = heapProbe,
        logicalProcessors = systemInfo.hardware.processor.logicalProcessorCount,
        enumerateProcesses = oshiEnumerate(systemInfo),
    )

    /** Prior CPU sample per process, keyed by (pid, startTime) to survive PID reuse (KTD-4). */
    private val priorSamples = mutableMapOf<ProcessKey, PriorSample>()

    override fun currentProcesses(): List<GradleProcess> {
        val now = clock()
        val seen = mutableSetOf<ProcessKey>()
        val result = mutableListOf<GradleProcess>()

        for (info in enumerateProcesses()) {
            val key = ProcessKey(info.pid, info.startTimeMs)
            seen += key
            val prior = priorSamples[key]
            // Classify before any heap attachment (issue #168).
            val snapshot = ProcessSnapshotBuilder.build(info, prior, now, logicalProcessors)
            priorSamples[key] = PriorSample(info.cpuTimeMs, now)
            if (snapshot != null) {
                // Live heap is orthogonal to RSS / -Xmx; probe failures stay unavailable (not zero).
                // Scope Attach/JMX to daemon JVMs (issue #159); wrappers/workers stay unavailable.
                val liveHeap = when (snapshot.type) {
                    ProcessType.GRADLE_DAEMON, ProcessType.KOTLIN_DAEMON ->
                        heapProbe.probe(info.pid, now)
                    else -> LiveJvmHeap.unavailable(now)
                }
                result += snapshot.copy(liveHeap = liveHeap)
            }
        }

        // Drop prior samples for processes that have disappeared.
        priorSamples.keys.retainAll(seen)
        heapProbe.retainOnly(seen.map { it.pid }.toSet())
        return result
    }

    /** Compatibility alias for [currentProcesses]. */
    fun poll(): List<GradleProcess> = currentProcesses()

    private data class ProcessKey(val pid: Long, val startTimeMs: Long)

    private companion object {
        private fun oshiEnumerate(systemInfo: SystemInfo): () -> List<ProcessInfo> {
            val os = systemInfo.operatingSystem
            val selfPid: Int = os.processId
            val selfUid: String? = runCatching { os.getProcess(selfPid).userID }.getOrNull()
            return {
                buildList {
                    for (p in os.processes) {
                        if (p.processID == selfPid) continue // don't monitor ourselves
                        if (selfUid != null && p.userID != selfUid) continue
                        add(OshiProcessInfo(p))
                    }
                }
            }
        }
    }

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
