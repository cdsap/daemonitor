package io.github.cdsap.daemonitor.ui.settings

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstallMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Immutable state the Settings screen renders. */
data class SettingsUiState(
    val retentionDays: Long = Defaults.DEFAULT_RETENTION_DAYS,
    val appearance: AppearancePreference = AppearancePreference.SYSTEM,
    val updateState: UpdateUiState = UpdateUiState.NotChecked,
    val mcpEnabled: Boolean = false,
    val mcpPort: Int = Defaults.DEFAULT_MCP_PORT,
    val mcpToken: String = "",
    val mcpState: McpUiState = McpUiState.Stopped,
) {
    val updateNotificationCount: Int
        get() = when (updateState) {
            is UpdateUiState.Available,
            is UpdateUiState.Downloading,
            is UpdateUiState.ReadyToInstall -> 1
            else -> 0
        }
}

sealed interface McpUiState {
    data object Stopped : McpUiState
    data object Starting : McpUiState
    data class Running(val endpoint: String) : McpUiState
    data class Failed(val message: String) : McpUiState
}

sealed interface UpdateUiState {
    data object NotChecked : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class Available(val candidate: UpdateCandidate) : UpdateUiState
    data class Downloading(val candidate: UpdateCandidate, val progress: Double?) : UpdateUiState
    data class ReadyToInstall(
        val candidate: UpdateCandidate,
        val staged: StagedUpdate? = null,
    ) : UpdateUiState
    data class Failed(val message: String, val releaseUrl: String? = null) : UpdateUiState
}

/**
 * Holds Settings state and forwards changes to [onRetentionChange], which the service wires to
 * persistence + an immediate purge. Framework-light so it stays unit-testable.
 *
 * Update check/download/apply and platform side effects go through [updateService].
 */
class SettingsViewModel(
    initial: SettingsUiState = SettingsUiState(),
    private val onRetentionChange: (Long) -> Unit = {},
    private val onAppearanceChange: (AppearancePreference) -> Unit = {},
    private val onMcpEnabledChange: (Boolean) -> Unit = {},
    private val updateService: UpdateService = UpdateService.inactive(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setRetentionDays(days: Long) {
        val clamped = days.coerceIn(Defaults.MIN_RETENTION_DAYS, Defaults.MAX_RETENTION_DAYS)
        if (clamped == _state.value.retentionDays) return
        _state.value = _state.value.copy(retentionDays = clamped)
        onRetentionChange(clamped)
    }

    fun setAppearance(appearance: AppearancePreference) {
        if (appearance == _state.value.appearance) return
        _state.value = _state.value.copy(appearance = appearance)
        onAppearanceChange(appearance)
    }

    fun setMcpEnabled(enabled: Boolean) {
        if (enabled == _state.value.mcpEnabled) return
        _state.value = _state.value.copy(
            mcpEnabled = enabled,
            mcpState = if (enabled) McpUiState.Starting else McpUiState.Stopped,
        )
        onMcpEnabledChange(enabled)
    }

    fun setMcpRunning(endpoint: String) {
        _state.value = _state.value.copy(mcpState = McpUiState.Running(endpoint))
    }

    fun setMcpRunningState(state: McpUiState) {
        _state.value = _state.value.copy(mcpState = state)
    }

    fun setMcpFailed(message: String) {
        _state.value = _state.value.copy(mcpState = McpUiState.Failed(message))
    }

    fun checkForUpdates() {
        if (_state.value.updateState == UpdateUiState.Checking) return
        _state.value = _state.value.copy(updateState = UpdateUiState.Checking)
        scope.launch {
            val nextState = runCatching { updateService.check().toUiState() }
                .getOrElse { error ->
                    UpdateUiState.Failed(
                        error.message ?: error::class.simpleName ?: "Could not check for updates",
                    )
                }
            _state.value = _state.value.copy(updateState = nextState)
        }
    }

    fun openUpdate(candidate: UpdateCandidate) {
        if (_state.value.updateState is UpdateUiState.Downloading) return
        _state.value = _state.value.copy(updateState = UpdateUiState.Downloading(candidate, 0.0))
        scope.launch {
            runCatching {
                updateService.prepare(candidate) { progress ->
                    _state.value = _state.value.copy(updateState = UpdateUiState.Downloading(candidate, progress))
                }
            }.onSuccess { staged ->
                _state.value = _state.value.copy(
                    updateState = UpdateUiState.ReadyToInstall(candidate, staged),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    updateState = UpdateUiState.Failed(
                        message = error.message ?: error::class.simpleName ?: "Could not prepare the update",
                        releaseUrl = candidate.releaseUrl,
                    ),
                )
            }
        }
    }

    fun restartAndUpdate() {
        val ready = _state.value.updateState as? UpdateUiState.ReadyToInstall ?: return
        val staged = ready.staged
        if (staged == null || ready.candidate.installMode != UpdateInstallMode.Automatic) {
            _state.value = _state.value.copy(
                updateState = UpdateUiState.Failed(
                    message = "Automatic installation is not available for this update. Use the manual download instead.",
                    releaseUrl = ready.candidate.releaseUrl,
                ),
            )
            return
        }
        runCatching {
            updateService.applyAndRestart(staged)
        }.onFailure { error ->
            _state.value = _state.value.copy(
                updateState = UpdateUiState.Failed(
                    message = error.message ?: error::class.simpleName ?: "Could not restart to update",
                    releaseUrl = ready.candidate.releaseUrl,
                ),
            )
        }
    }

    fun openManualDownload(url: String? = releaseUrlFromState()) {
        val target = url ?: return
        runCatching { updateService.openReleaseUrl(target) }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    updateState = UpdateUiState.Failed(
                        message = error.message ?: error::class.simpleName ?: "Could not open the release page",
                        releaseUrl = target,
                    ),
                )
            }
    }

    private fun releaseUrlFromState(): String? = when (val state = _state.value.updateState) {
        is UpdateUiState.Available -> state.candidate.releaseUrl
        is UpdateUiState.Downloading -> state.candidate.releaseUrl
        is UpdateUiState.ReadyToInstall -> state.candidate.releaseUrl
        is UpdateUiState.Failed -> state.releaseUrl
        else -> null
    }

    private fun UpdateCheckResult.toUiState(): UpdateUiState = when (this) {
        is UpdateCheckResult.Available -> UpdateUiState.Available(candidate)
        is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(currentVersion)
        is UpdateCheckResult.UnsupportedPlatform -> UpdateUiState.Failed("Updates are not available for this platform yet")
        is UpdateCheckResult.Failed -> UpdateUiState.Failed(reason)
    }
}
