package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLog
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.ui.history.HistoryViewModel
import io.github.cdsap.daemonitor.ui.live.LiveViewModel
import io.github.cdsap.daemonitor.ui.settings.SettingsUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsViewModel
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
    private val settingsStore: SettingsStore = SettingsStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val liveViewModel = LiveViewModel()
    val historyViewModel = HistoryViewModel()

    /** Current retention window (days), loaded from settings and updated when the user changes it. */
    @Volatile private var retentionDays: Long = settingsStore.load().retentionDays

    val settingsViewModel = SettingsViewModel(
        initial = SettingsUiState(retentionDays = retentionDays),
        onRetentionChange = ::onRetentionChanged,
    )

    private var serviceScope: CoroutineScope? = null
    private var knownDaemonPids = emptySet<Long>()

    fun start(scope: CoroutineScope) {
        serviceScope = scope
        database.purgeOlderThan(clock(), retentionDays)
        // Load whatever history already exists, then keep it current from the poll loop.
        scope.launch(Dispatchers.IO) { refreshHistory() }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { pollOnce() }
                delay(Defaults.POLL_INTERVAL)
            }
        }
    }

    /** User changed the retention window: persist it, purge anything now out of range, and refresh
     *  the Historical tab so the shortened window takes effect immediately. */
    private fun onRetentionChanged(days: Long) {
        retentionDays = days
        settingsStore.save(io.github.cdsap.daemonitor.store.Settings(retentionDays = days))
        val scope = serviceScope ?: return
        scope.launch(Dispatchers.IO) {
            database.purgeOlderThan(clock(), days)
            refreshHistory()
        }
    }

    /** Pull the current builds + project list from the DB and push them to the History view model.
     *  Explicit (not reactive): the poll loop calls this right after inserting new builds, so the
     *  Historical tab updates within one poll instead of waiting on `asFlow` notifications that did
     *  not fire reliably with the JDBC SQLite driver. */
    private suspend fun refreshHistory() {
        val builds = database.recentBuilds()
        val projects = database.distinctProjects()
        withContext(Dispatchers.Main) {
            historyViewModel.onBuilds(builds)
            historyViewModel.onProjects(projects)
        }
    }

    /** One poll cycle, factored out for testability. */
    suspend fun pollOnce() {
        val now = clock()
        val processes = collector.poll()
        processes.forEach { database.insertSample(it, now) }

        val logs = logWatcher.discover()
        val insertedBuilds = processForBuilds(logs)

        // Surface the selected daemon's tail, if any is selected.
        val selectedTail = selectedDaemonTail(logs)
        withContext(Dispatchers.Main) {
            liveViewModel.onPoll(processes, selectedTail)
        }

        // A new build landed → push it to the Historical tab immediately.
        if (insertedBuilds) refreshHistory()
    }

    /** @return true if at least one build was inserted this cycle (drives the History refresh). */
    private fun processForBuilds(logs: List<DaemonLog>): Boolean {
        val currentPids = logs.map { it.pid }.toSet()
        var inserted = false

        for (log in logs) {
            val events = logWatcher.readNewEvents(log.path)
            if (events.isNotEmpty()) {
                aggregator.onEvents(log.pid, events).forEach { database.insertBuild(it); inserted = true }
            }
        }

        // Daemons that vanished since last poll → flush any in-flight build as interrupted.
        (knownDaemonPids - currentPids).forEach { gonePid ->
            aggregator.onDaemonGone(gonePid)?.let { database.insertBuild(it); inserted = true }
        }
        knownDaemonPids = currentPids
        return inserted
    }

    private fun selectedDaemonTail(logs: List<DaemonLog>): List<String> {
        val detail = liveViewModel.state.value.detail
        val pid = when (detail) {
            is io.github.cdsap.daemonitor.ui.live.DetailState.Selected -> detail.process.pid
            else -> return emptyList()
        }
        val log = logs.firstOrNull { it.pid == pid } ?: return emptyList()
        return logWatcher.tailFor(log.path)
    }

    companion object {
        /** Build a fully wired service against the real database. */
        fun create(database: WatcherDatabase = WatcherDatabase.open()): WatcherService =
            WatcherService(
                aggregator = BuildAggregator(
                    sampleProvider = database::samplesInWindow,
                    // The watcher's own env is the ambient baseline: any agent var already present
                    // here is inherited by every build it observes, so it can't single out a build.
                    ambientEnvNames = System.getenv().keys.toSet(),
                ),
                database = database,
            )
    }
}
