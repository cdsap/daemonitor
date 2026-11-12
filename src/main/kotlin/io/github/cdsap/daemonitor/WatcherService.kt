package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DaemonBuildSource
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.HostContextSource
import io.github.cdsap.daemonitor.ui.live.ContextState
import io.github.cdsap.daemonitor.application.DaemonLogTailResult
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.persistence.RetainedProcessSampleRepository
import io.github.cdsap.daemonitor.persistence.SettingsRepository
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.ui.history.HistoryViewModel
import io.github.cdsap.daemonitor.ui.live.DetailState
import io.github.cdsap.daemonitor.ui.live.LiveViewModel
import io.github.cdsap.daemonitor.ui.live.VisualTimeRange
import io.github.cdsap.daemonitor.ui.live.VisualTimelineViewModel
import io.github.cdsap.daemonitor.ui.settings.McpUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsUiState
import io.github.cdsap.daemonitor.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

/**
 * Thin desktop facade that composes focused application services and projects results into
 * ViewModels. Prefer the extracted services for new call sites.
 */
class WatcherService(
    private val runtime: WatcherRuntime,
    private val hostContextSource: HostContextSource? = null,
    private val historyService: HistoryService,
    private val settingsService: SettingsService,
    private val mcpController: McpServiceController,
    private val updateService: UpdateService,
    private val monitoringService: MonitoringService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
    private val daemonBuildSource: DaemonBuildSource? = null,
    private val processSampleRepository: RetainedProcessSampleRepository? = null,
) {
    val liveViewModel = LiveViewModel()
    val visualTimelineViewModel = VisualTimelineViewModel()
    val historyViewModel = HistoryViewModel()

    private val initialSettings = settingsService.load()

    @Volatile
    private var latestDaemonLogs = emptyList<DaemonLog>()

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
        latestDaemonLogs = result.daemonLogs
        val selectedTail = selectedDaemonTail(result)
        withContext(uiDispatcher) {
            liveViewModel.onPoll(result.processes, selectedTail, result.daemonLogs)
        }
        if (result.buildsChanged) refreshHistory()
    }

    internal suspend fun pollSafely() {
        monitoringService.pollSafely(
            onResult = { result ->
                latestDaemonLogs = result.daemonLogs
                val selectedTail = selectedDaemonTail(result)
                withContext(uiDispatcher) {
                    liveViewModel.onPoll(result.processes, selectedTail, result.daemonLogs)
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

    fun select(pid: Long) {
        val source = hostContextSource
        val generation = liveViewModel.select(pid, source != null) ?: return
        val process = (liveViewModel.state.value.detail as? DetailState.Selected)?.process?.takeIf { it.pid == pid } ?: return
        if (source != null) {
            monitoringService.launchIo {
                val result = try { ContextState.Loaded(source.capabilities()) } catch (e: CancellationException) { throw e } catch (_: Exception) { ContextState.Error }
                withContext(uiDispatcher) { liveViewModel.onCapabilities(generation, result) }
            }
            monitoringService.launchIo {
                while (isActive) {
                    if (!withContext(uiDispatcher) { liveViewModel.isContextSelection(generation) }) break
                    val result = try { ContextState.Loaded(source.host()) } catch (e: CancellationException) { throw e } catch (_: Exception) { ContextState.Error }
                    val active = withContext(uiDispatcher) {
                        liveViewModel.onHost(generation, result)
                        liveViewModel.isContextSelection(generation)
                    }
                    if (!active) break
                    delay(30_000)
                }
            }
        }
        if (process.type == ProcessType.GRADLE_DAEMON) {
            monitoringService.launchIo {
                val builds = try {
                    if (process.startTimeMs > 0) daemonBuildSource?.buildsForDaemon(pid, process.startTimeMs, 20) else null
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
                withContext(uiDispatcher) { liveViewModel.onRecentBuilds(generation, process, builds) }
            }
        }
        monitoringService.launchIo {
            val tail = runtime.tailFor(latestDaemonLogs, pid)
            withContext(uiDispatcher) { liveViewModel.onTail(pid, tail) }
        }
    }

    fun setVisualTimeRange(range: VisualTimeRange) {
        val request = visualTimelineViewModel.selectRange(range) ?: return
        val durationMs = range.durationMs ?: return
        monitoringService.launchIo {
            val toMs = clock()
            val samples = try {
                processSampleRepository?.samplesInRange(toMs - durationMs, toMs).orEmpty()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
            withContext(uiDispatcher) {
                visualTimelineViewModel.onSamples(request, range, samples)
            }
        }
    }

    private fun selectedDaemonTail(result: WatcherRuntime.PollResult): DaemonLogTailResult? {
        val detail = liveViewModel.state.value.detail
        val pid = when (detail) {
            is DetailState.Selected -> detail.process.pid
            else -> return null
        }
        return runtime.tailFor(result.daemonLogs, pid)
    }

    companion object {
        /** Build a fully wired desktop service via the application composition root. */
        fun create(): WatcherService = AppContainer().createDesktopService()

        internal fun forTests(
            runtime: WatcherRuntime,
            buildRepository: BuildRepository,
            retentionRepository: RetentionRepository,
            settingsRepository: SettingsRepository = SettingsStore(),
            clock: () -> Long = System::currentTimeMillis,
            pollAction: suspend () -> WatcherRuntime.PollResult = { runtime.pollOnce() },
            updateService: UpdateService = UpdateService.inactive(),
            mcpServerFactory: () -> io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer,
            uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            daemonBuildSource: DaemonBuildSource? = null,
            processSampleRepository: RetainedProcessSampleRepository? = null,
        ): WatcherService {
            return WatcherService(
                runtime = runtime,
                historyService = HistoryService(buildRepository),
                settingsService = SettingsService(settingsRepository, retentionRepository, clock),
                mcpController = McpServiceController.create(mcpServerFactory),
                updateService = updateService,
                monitoringService = MonitoringService(
                    pollAction = pollAction,
                    ioDispatcher = ioDispatcher,
                ),
                uiDispatcher = uiDispatcher,
                clock = clock,
                daemonBuildSource = daemonBuildSource,
                processSampleRepository = processSampleRepository,
            )
        }
    }
}
