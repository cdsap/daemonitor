package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.PollMonitoring
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.store.WatcherDatabase

/**
 * Compatibility factory that wires default infrastructure adapters into [PollMonitoring].
 * Composition-root extraction can replace this later without changing the use case.
 */
object WatcherRuntime {
    fun create(database: WatcherDatabase): PollMonitoring = PollMonitoring(
        processSource = ProcessCollector(),
        logSource = DaemonLogWatcher(),
        builds = database,
        samples = database,
        aggregator = BuildAggregator(
            sampleProvider = database::samplesInWindow,
            ambientEnvNames = System.getenv().keys.toSet(),
        ),
    )
}
