package com.gradlewatcher.domain

import com.gradlewatcher.domain.model.Build
import com.gradlewatcher.domain.model.BuildEnvNames
import com.gradlewatcher.domain.model.BuildEvent
import com.gradlewatcher.domain.model.BuildStart
import com.gradlewatcher.domain.model.BusyMark
import com.gradlewatcher.domain.model.DaemonContextEvent
import com.gradlewatcher.domain.model.FinalStatus
import com.gradlewatcher.domain.model.IdleMark
import com.gradlewatcher.domain.model.Outcome
import com.gradlewatcher.domain.model.Source

/** Provides RSS + CPU samples for a PID within a time window (backed by the DB in production). */
typealias SampleProvider = (pid: Long, startMs: Long, endMs: Long) -> List<Pair<Long, Double?>>

/**
 * Correlates daemon-log events with poll samples into confirmed [Build] records (U5 / KTD-1).
 *
 * Keystone rule: a busy→idle bracket is only a *candidate*; a [Build] is emitted only when a
 * [BuildStart] marker was seen inside it (discards phantom brackets from expiration/health cycles).
 * The outcome line is optional enrichment bound to the open window; absent it, status is
 * COMPLETED_NO_OUTCOME. Peaks come from samples inside the window — empty means a sub-poll build
 * (null peaks, KTD-2). Daemon identity is the context uid, never the project path (HTD).
 */
class BuildAggregator(
    private val sampleProvider: SampleProvider = { _, _, _ -> emptyList() },
) {
    private val daemons = mutableMapOf<Long, DaemonState>()

    fun onEvents(daemonPid: Long, events: List<BuildEvent>): List<Build> {
        val state = daemons.getOrPut(daemonPid) { DaemonState() }
        val emitted = mutableListOf<Build>()

        for (event in events) {
            when (event) {
                is DaemonContextEvent -> event.uid?.let { state.uid = it }

                is BusyMark -> {
                    // If a prior qualified window never saw its idle marker (e.g. a missed line),
                    // flush it now using this busy mark as the proxy end, rather than dropping it.
                    state.window?.takeIf { it.qualified }?.let { w ->
                        emitted += w.toBuild(daemonPid, state.uid, endMs = event.timestampMs, sampleProvider)
                    }
                    state.window = Window(busyTimeMs = event.timestampMs)
                }

                is BuildStart -> state.window?.let { w ->
                    w.qualified = true
                    w.buildId = event.buildId
                    w.currentDir = event.currentDir ?: w.currentDir
                }

                is BuildEnvNames -> state.window?.let { it.envNames = event.envNames }

                is Outcome -> state.window?.let {
                    it.outcomeSuccess = event.success
                    it.outcomeDurationSeconds = event.durationSeconds
                }

                is IdleMark -> {
                    val w = state.window
                    if (w != null && w.qualified) {
                        emitted += w.toBuild(daemonPid, state.uid, endMs = event.timestampMs, sampleProvider)
                    }
                    state.window = null
                }
            }
        }
        return emitted
    }

    /** Daemon PID disappeared: emit an interrupted build if one was in flight. */
    fun onDaemonGone(daemonPid: Long): Build? {
        val state = daemons.remove(daemonPid) ?: return null
        val w = state.window ?: return null
        if (!w.qualified) return null
        return w.toBuild(daemonPid, state.uid, endMs = null, sampleProvider, interrupted = true)
    }

    private class DaemonState(
        var uid: String? = null,
        var window: Window? = null,
    )

    private class Window(
        val busyTimeMs: Long,
        var qualified: Boolean = false,
        var buildId: String? = null,
        var currentDir: String? = null,
        var envNames: List<String> = emptyList(),
        var outcomeSuccess: Boolean? = null,
        var outcomeDurationSeconds: Double? = null,
    ) {
        fun toBuild(
            daemonPid: Long,
            uid: String?,
            endMs: Long?,
            sampleProvider: SampleProvider,
            interrupted: Boolean = false,
        ): Build {
            val samples = endMs?.let { sampleProvider(daemonPid, busyTimeMs, it) } ?: emptyList()
            val rss = samples.map { it.first }
            val cpu = samples.mapNotNull { it.second }

            val status = when {
                interrupted -> FinalStatus.INTERRUPTED
                outcomeSuccess == true -> FinalStatus.SUCCESS
                outcomeSuccess == false -> FinalStatus.FAILED
                else -> FinalStatus.COMPLETED_NO_OUTCOME
            }
            val duration = outcomeDurationSeconds
                ?: endMs?.let { (it - busyTimeMs) / 1000.0 }

            return Build(
                buildId = buildId ?: "$daemonPid-$busyTimeMs",
                daemonPid = daemonPid,
                daemonIdentity = uid,
                commandLine = null, // per-build command line is not in the daemon log; null by design
                workingDirectory = currentDir,
                projectPath = currentDir,
                startTimeMs = busyTimeMs,
                endTimeMs = endMs,
                durationSeconds = duration,
                peakMemoryMb = rss.maxOrNull(),
                avgMemoryMb = if (rss.isEmpty()) null else rss.average().toLong(),
                peakCpuPercent = cpu.maxOrNull(),
                inferredSource = SourceDetector.detect(envNames),
                finalStatus = status,
                logSnippet = null, // wired with a redacted snippet when the watcher supplies one
            )
        }
    }
}
