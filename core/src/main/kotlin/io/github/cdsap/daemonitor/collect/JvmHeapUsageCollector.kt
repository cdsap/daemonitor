package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-aware, refresh-throttled cache in front of a [JvmHeapProbe].
 *
 * Entries are keyed by [ProcessIdentity] so a reused PID cannot return another process's sample
 * during the refresh window. [retainOnly] drops entries for processes that disappeared (and
 * forwards the same set to the underlying probe so connector state stays bounded).
 */
class JvmHeapUsageCollector(
    private val probe: JvmHeapProbe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
) {
    private val samples = ConcurrentHashMap<ProcessIdentity, CachedSample>()

    fun read(pid: Long, startTimeMs: Long, sampledAtMs: Long = clock()): LiveJvmHeap {
        val key = ProcessIdentity(pid, startTimeMs)
        val now = clock()
        val cached = samples[key]
        if (cached != null && now - cached.atMs < refreshIntervalMs) {
            return cached.value
        }
        val value = probe.probe(pid, startTimeMs, sampledAtMs)
        samples[key] = CachedSample(atMs = now, value = value)
        return value
    }

    fun forget(pid: Long, startTimeMs: Long) {
        samples.remove(ProcessIdentity(pid, startTimeMs))
    }

    fun retainOnly(liveProcesses: Collection<ProcessIdentity>) {
        val live = liveProcesses.toSet()
        samples.keys.retainAll(live)
        probe.retainOnly(live)
    }

    /** Test / diagnostics: number of cached process identities. */
    internal fun cachedSize(): Int = samples.size

    private data class CachedSample(val atMs: Long, val value: LiveJvmHeap)

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS = 10_000L
    }
}
