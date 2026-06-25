package io.github.cdsap.daemonitor.ui.settings

import io.github.cdsap.daemonitor.Defaults
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {

    @Test
    fun `setting retention updates state and notifies once`() {
        val changes = mutableListOf<Long>()
        val vm = SettingsViewModel(onRetentionChange = { changes += it })
        vm.setRetentionDays(30)
        assertEquals(30L, vm.state.value.retentionDays)
        assertEquals(listOf(30L), changes)
    }

    @Test
    fun `setting the same value is a no-op`() {
        val changes = mutableListOf<Long>()
        val vm = SettingsViewModel(
            initial = SettingsUiState(retentionDays = 15),
            onRetentionChange = { changes += it },
        )
        vm.setRetentionDays(15)
        assertEquals(emptyList(), changes)
    }

    @Test
    fun `out-of-range value is clamped`() {
        val vm = SettingsViewModel()
        vm.setRetentionDays(9_999)
        assertEquals(Defaults.MAX_RETENTION_DAYS, vm.state.value.retentionDays)
    }
}
