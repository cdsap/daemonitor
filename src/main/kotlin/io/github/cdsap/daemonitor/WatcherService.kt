package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.store.Settings
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.ui.history.HistoryViewModel
import io.github.cdsap.daemonitor.ui.live.LiveViewModel
import io.github.cdsap.daemonitor.ui.settings.SettingsUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop adapter that runs [WatcherRuntime] on [Dispatchers.IO] and projects results into UI state. */
class WatcherService(
    private val runtime: WatcherRuntime,
    private val database: WatcherDatabase,
    private val settingsStore: SettingsStore = SettingsStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollAction: suspend () -> WatcherRuntime.PollResult = { runtime.pollOnce() },
) {
    val liveViewModel = LiveViewModel()
    val historyViewModel = HistoryViewModel()

    /** Current retention window (days), loaded from settings and updated when the user changes it. */
    private val initialSettings = settingsStore.load()
    @Volatile private var retentionDays: Long = initialSettings.retentionDays

    val settingsViewModel = SettingsViewModel(
        initial = SettingsUiState(retentionDays = retentionDays, appearance = initialSettings.appearance),
        onRetentionChange = ::onRetentionChanged,
        onAppearanceChange = ::onAppearanceChanged,
    )

    private var serviceScope: CoroutineScope? = null
    fun start(scope: CoroutineScope) {
        serviceScope = scope
        database.purgeOlderThan(clock(), retentionDays)
        // Load whatever history already exists, then keep it current from the poll loop.
        scope.launch(Dispatchers.IO) { refreshHistory() }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollSafely()
                delay(Defaults.POLL_INTERVAL)
            }
        }
    }

    /** User changed the retention window: persist it, purge anything now out of range, and refresh
     *  the Historical tab so the shortened window takes effect immediately. */
    private fun onRetentionChanged(days: Long) {
        retentionDays = days
        saveSettings()
        val scope = serviceScope ?: return
        scope.launch(Dispatchers.IO) {
            database.purgeOlderThan(clock(), days)
            refreshHistory()
        }
    }

    private fun onAppearanceChanged(appearance: AppearancePreference) {
        saveSettings(appearance)
    }

    private fun saveSettings(appearance: AppearancePreference = settingsViewModel.state.value.appearance) {
        settingsStore.save(
            Settings(
                retentionDays = retentionDays,
                appearance = appearance,
            ),
        )
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
        val result = pollAction()

        // Surface the selected daemon's tail, if any is selected.
        val selectedTail = selectedDaemonTail(result)
        withContext(Dispatchers.Main) {
            liveViewModel.onPoll(result.processes, selectedTail)
        }

        // A new build landed → push it to the Historical tab immediately.
        if (result.buildsChanged) refreshHistory()
    }

    /** Run one retryable desktop poll and expose only a non-sensitive failure classification. */
    internal suspend fun pollSafely() {
        try {
            pollOnce()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val errorType = error::class.simpleName ?: "UnknownError"
            withContext(Dispatchers.Main) {
                liveViewModel.onPollFailure(clock(), errorType)
            }
        }
    }

    private fun selectedDaemonTail(result: WatcherRuntime.PollResult): List<String> {
        val detail = liveViewModel.state.value.detail
        val pid = when (detail) {
            is io.github.cdsap.daemonitor.ui.live.DetailState.Selected -> detail.process.pid
            else -> return emptyList()
        }
        return runtime.tailFor(result.daemonLogs, pid)
    }

    companion object {
        /** Build a fully wired service against the real database. */
        fun create(database: WatcherDatabase = WatcherDatabase.open()): WatcherService =
            WatcherService(
                runtime = WatcherRuntime.create(database),
                database = database,
            )
    }
}
