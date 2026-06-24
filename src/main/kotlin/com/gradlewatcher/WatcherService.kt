package com.gradlewatcher

import com.gradlewatcher.collect.DaemonLog
import com.gradlewatcher.collect.DaemonLogWatcher
import com.gradlewatcher.collect.ProcessCollector
import com.gradlewatcher.domain.BuildAggregator
import com.gradlewatcher.store.WatcherDatabase
import com.gradlewatcher.ui.live.LiveViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application coordinator (U7 wiring). Runs the periodic poll on [Dispatchers.IO]: collect
 * processes → persist samples; read daemon-log events → aggregate builds → persist; feed the
 * [LiveViewModel]. Build *existence* comes from the log (KTD-1); resource metrics from polling
 * (KTD-2). Heavy I/O stays off the UI thread (Compose best practice).
 */
class WatcherService(
    private val collector: ProcessCollector = ProcessCollector(),
    private val logWatcher: DaemonLogWatcher = DaemonLogWatcher(),
    private val aggregator: BuildAggregator,
    private val database: WatcherDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val liveViewModel = LiveViewModel()

    private var knownDaemonPids = emptySet<Long>()

    fun start(scope: CoroutineScope) {
        database.purgeOlderThan(clock())
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { pollOnce() }
                delay(Defaults.POLL_INTERVAL)
            }
        }
    }

    /** One poll cycle, factored out for testability. */
    suspend fun pollOnce() {
        val now = clock()
        val processes = collector.poll()
        processes.forEach { database.insertSample(it, now) }

        val logs = logWatcher.discover()
        processForBuilds(logs)

        // Surface the selected daemon's tail, if any is selected.
        val selectedTail = selectedDaemonTail(logs)
        withContext(Dispatchers.Main) {
            liveViewModel.onPoll(processes, selectedTail)
        }
    }

    private fun processForBuilds(logs: List<DaemonLog>) {
        val currentPids = logs.map { it.pid }.toSet()

        for (log in logs) {
            val events = logWatcher.readNewEvents(log.path)
            if (events.isNotEmpty()) {
                aggregator.onEvents(log.pid, events).forEach { database.insertBuild(it) }
            }
        }

        // Daemons that vanished since last poll → flush any in-flight build as interrupted.
        (knownDaemonPids - currentPids).forEach { gonePid ->
            aggregator.onDaemonGone(gonePid)?.let { database.insertBuild(it) }
        }
        knownDaemonPids = currentPids
    }

    private fun selectedDaemonTail(logs: List<DaemonLog>): List<String> {
        val detail = liveViewModel.state.value.detail
        val pid = when (detail) {
            is com.gradlewatcher.ui.live.DetailState.Selected -> detail.process.pid
            else -> return emptyList()
        }
        val log = logs.firstOrNull { it.pid == pid } ?: return emptyList()
        return logWatcher.tailFor(log.path)
    }

    companion object {
        /** Build a fully wired service against the real database. */
        fun create(database: WatcherDatabase = WatcherDatabase.open()): WatcherService =
            WatcherService(
                aggregator = BuildAggregator(sampleProvider = database::samplesInWindow),
                database = database,
            )
    }
}
