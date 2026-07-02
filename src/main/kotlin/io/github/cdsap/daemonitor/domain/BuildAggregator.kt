package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildEnvNames
import io.github.cdsap.daemonitor.domain.model.BuildEvent
import io.github.cdsap.daemonitor.domain.model.BuildStart
import io.github.cdsap.daemonitor.domain.model.BusyMark
import io.github.cdsap.daemonitor.domain.model.DaemonContextEvent
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.IdleMark
import io.github.cdsap.daemonitor.domain.model.Outcome
import io.github.cdsap.daemonitor.domain.model.Source

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
    /** Env-var names in the watcher's own process — subtracted from each build's env before agent
     *  fingerprinting so an ambient agent session is not mis-attributed to every build it spawns. */
    private val ambientEnvNames: Set<String> = emptySet(),
) {
    private val daemons = mutableMapOf<Long, DaemonState>()

    fun onEvents(daemonPid: Long, events: List<BuildEvent>): List<Build> {
        return events.flatMap { processLogLine(daemonPid, line = null, event = it) }
    }

    /** Correlate one redacted log line with its event while preserving window boundaries. */
    fun onLogLine(daemonPid: Long, line: String, event: BuildEvent?): List<Build> =
        processLogLine(daemonPid, line, event)

    private fun processLogLine(daemonPid: Long, line: String?, event: BuildEvent?): List<Build> {
        val state = daemons.getOrPut(daemonPid) { DaemonState() }
        val emitted = mutableListOf<Build>()

        // A busy marker belongs to the window it opens. Every other line belongs to the currently
        // open window, including the idle marker that closes it.
        if (event !is BusyMark) state.window?.let { window -> line?.let(window::appendLogLine) }

        if (event != null) {
            when (event) {
                is DaemonContextEvent -> event.uid?.let { state.uid = it }

                is BusyMark -> {
                    // If a prior qualified window never saw its idle marker (e.g. a missed line),
                    // flush it now using this busy mark as the proxy end, rather than dropping it.
                    state.window?.takeIf { it.qualified }?.let { w ->
                        emitted += w.toBuild(daemonPid, state.uid, endMs = event.timestampMs, sampleProvider, ambientEnvNames)
                    }
                    state.window = Window(busyTimeMs = event.timestampMs)
                    line?.let { state.window?.appendLogLine(it) }
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
                        emitted += w.toBuild(daemonPid, state.uid, endMs = event.timestampMs, sampleProvider, ambientEnvNames)
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
        return w.toBuild(daemonPid, state.uid, endMs = null, sampleProvider, ambientEnvNames, interrupted = true)
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
        private val logLines = ArrayDeque<String>()
        private var logChars = 0

        fun appendLogLine(line: String) {
            val boundedLine = line.takeLast(Defaults.LOG_SNIPPET_CHARS)
            logLines.addLast(boundedLine)
            logChars += boundedLine.length + if (logLines.size > 1) 1 else 0
            while (logLines.size > Defaults.LOG_SNIPPET_LINES || logChars > Defaults.LOG_SNIPPET_CHARS) {
                val removed = logLines.removeFirst()
                logChars -= removed.length + if (logLines.isNotEmpty()) 1 else 0
            }
        }

        fun toBuild(
            daemonPid: Long,
            uid: String?,
            endMs: Long?,
            sampleProvider: SampleProvider,
            ambientEnvNames: Set<String>,
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
            val agentAttr = AgentDetector.detect(envNames, ambientEnvNames)

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
                logSnippet = logLines.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                agent = agentAttr?.agent,
                agentProvider = agentAttr?.provider,
            )
        }
    }
}
