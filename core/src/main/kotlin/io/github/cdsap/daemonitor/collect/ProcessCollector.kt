package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.domain.LiveMetricLabels
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
    heapProbe: JvmHeapProbe,
    private val logicalProcessors: Int,
    private val enumerateProcesses: () -> List<ProcessInfo>,
    heapRefreshIntervalMs: Long = JvmHeapUsageCollector.DEFAULT_REFRESH_INTERVAL_MS,
) : ProcessSource {
    constructor(
        systemInfo: SystemInfo = SystemInfo(),
        clock: () -> Long = System::currentTimeMillis,
        heapProbe: JvmHeapProbe = AttachJvmHeapProbe(),
        heapRefreshIntervalMs: Long = JvmHeapUsageCollector.DEFAULT_REFRESH_INTERVAL_MS,
    ) : this(
        clock = clock,
        heapProbe = heapProbe,
        logicalProcessors = systemInfo.hardware.processor.logicalProcessorCount,
        enumerateProcesses = oshiEnumerate(systemInfo),
        heapRefreshIntervalMs = heapRefreshIntervalMs,
    )

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
        val infos = enumerateProcesses()
        // Hard argv types first so native Bazel test leaves can require a BAZEL_TEST parent.
        val hardTypeByPid = infos.mapNotNull { info ->
            GradleProcessClassifier.classify(info.commandLine)?.let { info.pid to it }
        }.toMap()

        for (info in infos) {
            val key = ProcessIdentity(info.pid, info.startTimeMs)
            seen += key
            val prior = priorSamples[key]
            // Classify before any heap attachment (issue #168).
            val type = hardTypeByPid[info.pid]
                ?: GradleProcessClassifier.promoteBazelNativeTest(
                    info.commandLine,
                    hardTypeByPid[info.parentPid],
                )
            val snapshot = if (type != null) {
                ProcessSnapshotBuilder.build(info, prior, now, logicalProcessors, typeOverride = type)
            } else {
                null
            }
            priorSamples[key] = PriorSample(info.cpuTimeMs, now)
            if (snapshot != null) {
                // Live heap is orthogonal to RSS / -Xmx; probe failures stay unavailable (not zero).
                // Keep in sync with poll.ShouldProbeLiveHeap / LiveMetricLabels.isLiveHeapProbedType.
                val liveHeap = if (LiveMetricLabels.isLiveHeapProbedType(snapshot.type)) {
                    heapUsageCollector.read(info.pid, info.startTimeMs, now)
                } else {
                    LiveJvmHeap.unavailable(now)
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
