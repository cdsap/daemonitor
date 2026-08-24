package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.ui.history.HistoryViewModel
import io.github.cdsap.daemonitor.ui.live.LiveViewModel
import io.github.cdsap.daemonitor.ui.settings.McpUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Thin desktop facade that composes focused application services and projects results into
 * ViewModels. Prefer the extracted services for new call sites.
 */
class WatcherService(
    private val runtime: WatcherRuntime,
    private val historyService: HistoryService,
    private val settingsService: SettingsService,
    private val mcpController: McpServiceController,
    private val updateService: UpdateService,
    private val monitoringService: MonitoringService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val liveViewModel = LiveViewModel()
    val historyViewModel = HistoryViewModel()

    private val initialSettings = settingsService.load()

    val settingsViewModel = SettingsViewModel(
        initial = SettingsUiState(
            retentionDays = initialSettings.retentionDays,
            appearance = initialSettings.appearance,
            mcpEnabled = initialSettings.mcpEnabled,
            mcpPort = initialSettings.mcpPort,
            mcpToken = initialSettings.mcpToken,
        ),
        onRetentionChange = ::onRetentionChanged,
        onAppearanceChange = settingsService::updateAppearance,
        onMcpEnabledChange = ::onMcpEnabledChanged,
        updateService = updateService,
        scope = CoroutineScope(SupervisorJob() + uiDispatcher),
    )

    fun start(scope: CoroutineScope) {
        settingsViewModel.checkForUpdates()
        settingsService.purgeNow()
        monitoringService.start(scope) { pollSafely() }
        if (settingsViewModel.state.value.mcpEnabled) startMcpServer()
        monitoringService.launchIo { refreshHistory() }
    }

    /**
     * Stops MCP and cancels background poll/history work, waiting for it to finish.
     * Call this before closing the database so temp-dir cleanup cannot race in-flight SQLite access.
     */
    suspend fun stop() {
        mcpController.stop()
        monitoringService.stop()
    }

    private fun onRetentionChanged(days: Long) {
        settingsService.updateRetention(days)
        monitoringService.launchIo { refreshHistory() }
    }

    private fun onMcpEnabledChanged(enabled: Boolean) {
        settingsService.updateMcpEnabled(enabled)
        if (monitoringService.boundScope == null) return
        if (enabled) {
            startMcpServer()
        } else {
            mcpController.stop()
            settingsViewModel.setMcpRunningState(McpUiState.Stopped)
        }
    }

    private fun startMcpServer() {
        val existing = mcpController.endpoint
        if (existing != null) {
            settingsViewModel.setMcpRunning(existing)
            return
        }
        monitoringService.launchIo {
            withContext(uiDispatcher) {
                settingsViewModel.setMcpRunningState(McpUiState.Starting)
            }
            val state = settingsViewModel.state.value
            mcpController.start(port = state.mcpPort, token = state.mcpToken)
                .onSuccess { endpoint ->
                    if (!settingsViewModel.state.value.mcpEnabled) {
                        mcpController.stop()
                        return@onSuccess
                    }
                    withContext(uiDispatcher) {
                        settingsViewModel.setMcpRunning(endpoint)
                    }
                }
                .onFailure { error ->
                    withContext(uiDispatcher) {
                        settingsViewModel.setMcpFailed(
                            error.message ?: error::class.simpleName ?: "Could not start MCP",
                        )
                    }
                }
        }
    }

    private suspend fun refreshHistory() {
        val builds = historyService.history()
        val projects = historyService.projects()
        withContext(uiDispatcher) {
            historyViewModel.onBuilds(builds)
            historyViewModel.onProjects(projects)
        }
    }

    suspend fun pollOnce() {
        val result = monitoringService.poll()
        val selectedTail = selectedDaemonTail(result)
        withContext(uiDispatcher) {
            liveViewModel.onPoll(result.processes, selectedTail)
        }
        if (result.buildsChanged) refreshHistory()
    }

    internal suspend fun pollSafely() {
        monitoringService.pollSafely(
            onResult = { result ->
                val selectedTail = selectedDaemonTail(result)
                withContext(uiDispatcher) {
                    liveViewModel.onPoll(result.processes, selectedTail)
                }
                if (result.buildsChanged) refreshHistory()
            },
            onFailure = { error ->
                val errorType = error::class.simpleName ?: "UnknownError"
                withContext(uiDispatcher) {
                    liveViewModel.onPollFailure(clock(), errorType)
                }
            },
        )
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
        /** Build a fully wired desktop service via the application composition root. */
        fun create(): WatcherService = AppContainer().createDesktopService()

        internal fun forTests(
            runtime: WatcherRuntime,
            database: WatcherDatabase,
            settingsStore: SettingsStore = SettingsStore(),
            clock: () -> Long = System::currentTimeMillis,
            pollAction: suspend () -> WatcherRuntime.PollResult = { runtime.pollOnce() },
            updateService: UpdateService = UpdateService.inactive(),
            mcpServerFactory: () -> io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer,
            uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): WatcherService {
            return WatcherService(
                runtime = runtime,
                historyService = HistoryService(database),
                settingsService = SettingsService(settingsStore, database, clock),
                mcpController = McpServiceController.create(mcpServerFactory),
                updateService = updateService,
                monitoringService = MonitoringService(
                    pollAction = pollAction,
                    ioDispatcher = ioDispatcher,
                ),
                uiDispatcher = uiDispatcher,
                clock = clock,
            )
        }
    }
}
