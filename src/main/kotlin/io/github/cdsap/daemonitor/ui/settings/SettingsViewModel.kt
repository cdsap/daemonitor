package io.github.cdsap.daemonitor.ui.settings

import io.github.cdsap.daemonitor.BuildInfo
import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.update.DesktopUpdateInstaller
import io.github.cdsap.daemonitor.update.GitHubReleaseUpdateSource
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstaller
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
) {
    val updateNotificationCount: Int
        get() = if (updateState is UpdateUiState.Available) 1 else 0
}

sealed interface UpdateUiState {
    data object NotChecked : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class Available(val candidate: UpdateCandidate) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Holds Settings state and forwards changes to [onRetentionChange], which the service wires to
 * persistence + an immediate purge. Framework-light so it stays unit-testable.
 */
class SettingsViewModel(
    initial: SettingsUiState = SettingsUiState(),
    private val onRetentionChange: (Long) -> Unit = {},
    private val onAppearanceChange: (AppearancePreference) -> Unit = {},
    private val updateChecker: suspend () -> UpdateCheckResult = {
        GitHubReleaseUpdateSource().check(BuildInfo.current.version)
    },
    private val updateInstaller: UpdateInstaller = DesktopUpdateInstaller(),
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

    fun checkForUpdates() {
        if (_state.value.updateState == UpdateUiState.Checking) return
        _state.value = _state.value.copy(updateState = UpdateUiState.Checking)
        scope.launch {
            val nextState = runCatching { updateChecker().toUiState() }
                .getOrElse { error ->
                    UpdateUiState.Failed(
                        error.message ?: error::class.simpleName ?: "Could not check for updates",
                    )
                }
            _state.value = _state.value.copy(updateState = nextState)
        }
    }

    fun openUpdate(candidate: UpdateCandidate) {
        runCatching { updateInstaller.open(candidate) }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    updateState = UpdateUiState.Failed(
                        error.message ?: error::class.simpleName ?: "Could not open the update",
                    ),
                )
            }
    }

    private fun UpdateCheckResult.toUiState(): UpdateUiState = when (this) {
        is UpdateCheckResult.Available -> UpdateUiState.Available(candidate)
        is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(currentVersion)
        is UpdateCheckResult.UnsupportedPlatform -> UpdateUiState.Failed("Updates are not available for this platform yet")
        is UpdateCheckResult.Failed -> UpdateUiState.Failed(reason)
    }
}
