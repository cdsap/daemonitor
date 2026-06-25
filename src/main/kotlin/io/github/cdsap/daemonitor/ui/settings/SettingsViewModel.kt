package io.github.cdsap.daemonitor.ui.settings

import io.github.cdsap.daemonitor.Defaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable state the Settings screen renders. */
data class SettingsUiState(
    val retentionDays: Long = Defaults.DEFAULT_RETENTION_DAYS,
)

/**
 * Holds Settings state and forwards changes to [onRetentionChange], which the service wires to
 * persistence + an immediate purge. Framework-light so it stays unit-testable.
 */
class SettingsViewModel(
    initial: SettingsUiState = SettingsUiState(),
    private val onRetentionChange: (Long) -> Unit = {},
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setRetentionDays(days: Long) {
        val clamped = days.coerceIn(Defaults.MIN_RETENTION_DAYS, Defaults.MAX_RETENTION_DAYS)
        if (clamped == _state.value.retentionDays) return
        _state.value = _state.value.copy(retentionDays = clamped)
        onRetentionChange(clamped)
    }
}
