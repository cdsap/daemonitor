package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.Build

/**
 * Defines which component owns monitoring persistence for a polling runtime.
 *
 * The remote-build variant owns the source used to import builds, keeping build-source selection
 * and persistence ownership as one valid configuration.
 */
sealed interface MonitoringMode {
    /** Persist samples and aggregate builds from daemon logs locally. */
    data object Local : MonitoringMode

    /** Persist samples locally and import completed builds from the external core. */
    data class RemoteBuilds(val source: BuildSource) : MonitoringMode

    /** The shared core owns both sample and build persistence. */
    data object SharedCoreOwnedPersistence : MonitoringMode
}

/**
 * Selected at composition time to process builds for a polling runtime.
 *
 * The processor also declares whether this runtime may persist process samples. Keeping those
 * decisions together prevents a build source and a separate persistence flag from describing an
 * invalid combination.
 */
interface BuildProcessingMode {
    val persistsSamples: Boolean

    fun process(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean
}

internal fun MonitoringMode.buildProcessingMode(
    builds: BuildWriter,
    logSource: DaemonLogSource,
    aggregator: BuildAggregator,
    retentionDays: () -> Long,
    clock: () -> Long,
): BuildProcessingMode = when (this) {
    MonitoringMode.Local -> LocalBuildProcessingMode(
        logSource = logSource,
        builds = builds,
        aggregator = aggregator,
        retentionDays = retentionDays,
        clock = clock,
    )
    is MonitoringMode.RemoteBuilds -> RemoteBuildProcessingMode(
        source = source,
        builds = builds,
        retentionDays = retentionDays,
        clock = clock,
    )
    MonitoringMode.SharedCoreOwnedPersistence -> CoreOwnedBuildProcessingMode
}

private class LocalBuildProcessingMode(
    private val logSource: DaemonLogSource,
    private val builds: BuildWriter,
    private val aggregator: BuildAggregator,
    private val retentionDays: () -> Long,
    private val clock: () -> Long,
) : BuildProcessingMode {
    override val persistsSamples = true
    private var knownDaemonPids = emptySet<Long>()

    override fun process(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean {
        var inserted = false
        // Only read tails for daemons we are tracking (live now, or known from a prior poll).
        // Discover may return hundreds of historical daemon-*.out.log paths under ~/.gradle;
        // reading all of them every cycle is too expensive for local IO and catastrophic for
        // GoCoreDaemonLogSource (HTTP tail per path — issue #221).
        val pidsToRead = activeDaemonPids + knownDaemonPids

        for (log in logs) {
            if (log.pid in pidsToRead) {
                val lines = logSource.readNewLines(log)
                if (lines.isNotEmpty()) {
                    lines.flatMap { aggregator.onLogLine(log.pid, it.text, it.event) }.forEach { build ->
                        if (saveIfWithinRetention(build)) inserted = true
                    }
                }
            }
            if (log.pid !in activeDaemonPids) {
                aggregator.onDaemonGone(log.pid)?.let { build ->
                    if (saveIfWithinRetention(build)) inserted = true
                }
            }
        }

        (knownDaemonPids - activeDaemonPids).forEach { gonePid ->
            aggregator.onDaemonGone(gonePid)?.let { build ->
                if (saveIfWithinRetention(build)) inserted = true
            }
        }
        knownDaemonPids = activeDaemonPids
        return inserted
    }

    private fun saveIfWithinRetention(build: Build): Boolean {
        val cutoff = RetentionPolicy.DEFAULT.cutoffEpochMs(clock(), retentionDays())
        if (build.startTimeMs < cutoff) return false
        builds.save(build)
        return true
    }
}

private class RemoteBuildProcessingMode(
    private val source: BuildSource,
    private val builds: BuildWriter,
    private val retentionDays: () -> Long,
    private val clock: () -> Long,
) : BuildProcessingMode {
    override val persistsSamples = true
    private var lastRemoteBuildFingerprint = emptyMap<String, String>()

    override fun process(logs: List<DaemonLog>, activeDaemonPids: Set<Long>): Boolean {
        val fingerprint = linkedMapOf<String, String>()
        for (build in source.recentBuilds()) {
            if (!saveIfWithinRetention(build)) continue
            fingerprint[build.buildId] = build.finalStatus.name
        }
        val changed = fingerprint != lastRemoteBuildFingerprint
        lastRemoteBuildFingerprint = fingerprint
        return changed
    }

    private fun saveIfWithinRetention(build: Build): Boolean {
        val cutoff = RetentionPolicy.DEFAULT.cutoffEpochMs(clock(), retentionDays())
        if (build.startTimeMs < cutoff) return false
        builds.save(build)
        return true
    }
}

private object CoreOwnedBuildProcessingMode : BuildProcessingMode {
    override val persistsSamples = false

    override fun process(logs: List<DaemonLog>, activeDaemonPids: Set<Long>) = false
}
