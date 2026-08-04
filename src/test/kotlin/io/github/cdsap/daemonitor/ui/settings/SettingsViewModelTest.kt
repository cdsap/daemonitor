package io.github.cdsap.daemonitor.ui.settings

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstaller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `setting appearance updates state and notifies once`() {
        val changes = mutableListOf<AppearancePreference>()
        val vm = SettingsViewModel(onAppearanceChange = { changes += it })
        vm.setAppearance(AppearancePreference.DARK)
        vm.setAppearance(AppearancePreference.DARK)
        assertEquals(AppearancePreference.DARK, vm.state.value.appearance)
        assertEquals(listOf(AppearancePreference.DARK), changes)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `checking for updates exposes available update state`() = runTest {
        val candidate = UpdateCandidate(
            version = "1.0.3",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.3-macos.dmg",
            downloadUrl = "https://example.com/Daemonitor-1.0.3-macos.dmg",
        )
        val vm = updateViewModel(
            result = UpdateCheckResult.Available(candidate),
            scope = this,
        )

        vm.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateUiState.Available(candidate), vm.state.value.updateState)
        assertEquals(1, vm.state.value.updateNotificationCount)
    }

    @Test
    fun `update notification count is only set for available updates`() {
        val candidate = UpdateCandidate(
            version = "1.0.3",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.3-macos.dmg",
            downloadUrl = "https://example.com/Daemonitor-1.0.3-macos.dmg",
        )

        assertEquals(0, SettingsUiState().updateNotificationCount)
        assertEquals(0, SettingsUiState(updateState = UpdateUiState.Checking).updateNotificationCount)
        assertEquals(0, SettingsUiState(updateState = UpdateUiState.UpToDate("1.0.2")).updateNotificationCount)
        assertEquals(0, SettingsUiState(updateState = UpdateUiState.Failed("No network")).updateNotificationCount)
        assertEquals(1, SettingsUiState(updateState = UpdateUiState.Available(candidate)).updateNotificationCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `opening an available update delegates to installer`() = runTest {
        val opened = mutableListOf<UpdateCandidate>()
        val candidate = UpdateCandidate(
            version = "1.0.3",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.3-linux.deb",
            downloadUrl = "https://example.com/Daemonitor-1.0.3-linux.deb",
        )
        val vm = SettingsViewModel(
            updateInstaller = UpdateInstaller { update, progress ->
                progress(0.5)
                opened += update
            },
            scope = this,
        )

        vm.openUpdate(candidate)
        advanceUntilIdle()

        assertEquals(listOf(candidate), opened)
        assertEquals(UpdateUiState.ReadyToInstall(candidate), vm.state.value.updateState)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `installer failures are surfaced in update state`() = runTest {
        val candidate = UpdateCandidate(
            version = "1.0.3",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.3-windows.msi",
            downloadUrl = "https://example.com/Daemonitor-1.0.3-windows.msi",
        )
        val vm = SettingsViewModel(
            updateInstaller = UpdateInstaller { _, _ -> error("No desktop") },
            scope = this,
        )

        vm.openUpdate(candidate)
        advanceUntilIdle()

        assertEquals(UpdateUiState.Failed("No desktop"), vm.state.value.updateState)
    }

    private fun updateViewModel(
        result: UpdateCheckResult,
        scope: TestScope,
    ): SettingsViewModel = SettingsViewModel(
        updateChecker = { result },
        updateInstaller = UpdateInstaller { _, _ -> },
        scope = scope,
    )
}
