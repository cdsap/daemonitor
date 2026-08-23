package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.persistence.Settings
import io.github.cdsap.daemonitor.persistence.SettingsRepository
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpHttpServer
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.ui.history.HistoryViewModel
import io.github.cdsap.daemonitor.ui.live.LiveViewModel
import io.github.cdsap.daemonitor.ui.settings.McpUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsViewModel
import io.github.cdsap.daemonitor.update.GitHubReleaseUpdateSource
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop adapter that runs [WatcherRuntime] on [Dispatchers.IO] and projects results into UI state. */
class WatcherService(
    private val runtime: WatcherRuntime,
    private val builds: BuildRepository,
    private val processSamples: ProcessSampleRepository,
    private val retention: RetentionRepository,
    private val settingsStore: SettingsRepository = SettingsStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollAction: suspend () -> WatcherRuntime.PollResult = { runtime.pollOnce() },
    private val updateChecker: suspend () -> UpdateCheckResult = {
        GitHubReleaseUpdateSource().check(BuildInfo.current.version)
    },
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val liveViewModel = LiveViewModel()
    val historyViewModel = HistoryViewModel()

    /** Current retention window (days), loaded from settings and updated when the user changes it. */
    private val initialSettings = settingsStore.load()
    @Volatile private var retentionDays: Long = initialSettings.retentionDays

    val settingsViewModel = SettingsViewModel(
        initial = SettingsUiState(
            retentionDays = retentionDays,
            appearance = initialSettings.appearance,
            mcpEnabled = initialSettings.mcpEnabled,
            mcpPort = initialSettings.mcpPort,
            mcpToken = initialSettings.mcpToken,
        ),
        onRetentionChange = ::onRetentionChanged,
        onAppearanceChange = ::onAppearanceChanged,
        onMcpEnabledChange = ::onMcpEnabledChanged,
        updateChecker = updateChecker,
        scope = CoroutineScope(SupervisorJob() + uiDispatcher),
    )

    private var serviceScope: CoroutineScope? = null
    private var serviceJob: Job? = null
    @Volatile private var mcpHttpServer: DaemonitorMcpHttpServer? = null

    fun start(scope: CoroutineScope) {
        serviceJob?.cancel()
        val job = SupervisorJob(scope.coroutineContext[Job])
        serviceJob = job
        val boundScope = CoroutineScope(scope.coroutineContext + job)
        serviceScope = boundScope
        settingsViewModel.checkForUpdates()
        if (settingsViewModel.state.value.mcpEnabled) startMcpServer(boundScope)
        retention.purgeOlderThan(clock(), retentionDays)
        // Load whatever history already exists, then keep it current from the poll loop.
        boundScope.launch(ioDispatcher) { refreshHistory() }
        boundScope.launch(ioDispatcher) {
            while (isActive) {
                pollSafely()
                delay(MonitoringConfig.DEFAULT.pollInterval)
            }
        }
    }

    /**
     * Cancels background poll/history work and waits for it to finish.
     * Call this before closing the database so temp-dir cleanup cannot race in-flight SQLite access.
     */
    suspend fun stop() {
        mcpHttpServer?.close()
        mcpHttpServer = null
        val job = serviceJob
        serviceJob = null
        serviceScope = null
        job?.cancelAndJoin()
    }

    /** User changed the retention window: persist it, purge anything now out of range, and refresh
     *  the Historical tab so the shortened window takes effect immediately. */
    private fun onRetentionChanged(days: Long) {
        retentionDays = days
        saveSettings()
        val scope = serviceScope ?: return
        scope.launch(ioDispatcher) {
            retention.purgeOlderThan(clock(), days)
            refreshHistory()
        }
    }

    private fun onAppearanceChanged(appearance: AppearancePreference) {
        saveSettings(appearance)
    }

    private fun onMcpEnabledChanged(enabled: Boolean) {
        saveSettings(mcpEnabled = enabled)
        val scope = serviceScope ?: return
        if (enabled) {
            startMcpServer(scope)
        } else {
            mcpHttpServer?.close()
            mcpHttpServer = null
            settingsViewModel.setMcpRunningState(McpUiState.Stopped)
        }
    }

    private fun startMcpServer(scope: CoroutineScope) {
        val existing = mcpHttpServer
        if (existing != null) {
            settingsViewModel.setMcpRunning(existing.endpoint)
            return
        }
        scope.launch(ioDispatcher) {
            withContext(uiDispatcher) {
                settingsViewModel.setMcpRunningState(McpUiState.Starting)
            }
            runCatching {
                val state = settingsViewModel.state.value
                DaemonitorMcpHttpServer.start(
                    port = state.mcpPort,
                    token = state.mcpToken,
                    server = DaemonitorMcpServer(builds, processSamples),
                )
            }.onSuccess { server ->
                if (!settingsViewModel.state.value.mcpEnabled) {
                    server.close()
                    return@onSuccess
                }
                mcpHttpServer = server
                withContext(uiDispatcher) {
                    settingsViewModel.setMcpRunning(server.endpoint)
                }
            }.onFailure { error ->
                withContext(uiDispatcher) {
                    settingsViewModel.setMcpFailed(error.message ?: error::class.simpleName ?: "Could not start MCP")
                }
            }
        }
    }

    private fun saveSettings(
        appearance: AppearancePreference = settingsViewModel.state.value.appearance,
        mcpEnabled: Boolean = settingsViewModel.state.value.mcpEnabled,
    ) {
        val state = settingsViewModel.state.value
        settingsStore.save(
            Settings(
                retentionDays = retentionDays,
                appearance = appearance,
                mcpEnabled = mcpEnabled,
                mcpPort = state.mcpPort,
                mcpToken = state.mcpToken,
            ),
        )
    }

    /** Pull the current builds + project list from the DB and push them to the History view model.
     *  Explicit (not reactive): the poll loop calls this right after inserting new builds, so the
     *  Historical tab updates within one poll instead of waiting on `asFlow` notifications that did
     *  not fire reliably with the JDBC SQLite driver. */
    private suspend fun refreshHistory() {
        val recentBuilds = builds.recent()
        val projects = builds.distinctProjects()
        withContext(uiDispatcher) {
            historyViewModel.onBuilds(recentBuilds)
            historyViewModel.onProjects(projects)
        }
    }

    /** One poll cycle, factored out for testability. */
    suspend fun pollOnce() {
        val result = pollAction()

        // Surface the selected daemon's tail, if any is selected.
        val selectedTail = selectedDaemonTail(result)
        withContext(uiDispatcher) {
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
            withContext(uiDispatcher) {
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
                builds = database,
                processSamples = database,
                retention = database,
            )
    }
}
