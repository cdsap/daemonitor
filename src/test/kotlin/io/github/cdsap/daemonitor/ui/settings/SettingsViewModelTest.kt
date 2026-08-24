package io.github.cdsap.daemonitor.ui.settings

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstallMode
import io.github.cdsap.daemonitor.update.UpdateInstaller
import io.github.cdsap.daemonitor.update.CpuArchitecture
import io.github.cdsap.daemonitor.update.DesktopPlatform
import io.github.cdsap.daemonitor.update.InstallationInfo
import io.github.cdsap.daemonitor.update.InstallationKind
import io.github.cdsap.daemonitor.update.UpdateArtifactRole
import java.nio.file.Path
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
        assertEquals(RetentionPolicy.DEFAULT.maxDays, vm.state.value.retentionDays)
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
    fun `enabling mcp updates state and notifies once`() {
        val changes = mutableListOf<Boolean>()
        val vm = SettingsViewModel(onMcpEnabledChange = { changes += it })
        vm.setMcpEnabled(true)
        vm.setMcpEnabled(true)
        assertEquals(true, vm.state.value.mcpEnabled)
        assertEquals(McpUiState.Starting, vm.state.value.mcpState)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `mcp runtime state can report running and failed`() {
        val vm = SettingsViewModel()
        vm.setMcpRunning("http://127.0.0.1:17333/mcp")
        assertEquals(McpUiState.Running("http://127.0.0.1:17333/mcp"), vm.state.value.mcpState)
        vm.setMcpFailed("Port already in use")
        assertEquals(McpUiState.Failed("Port already in use"), vm.state.value.mcpState)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `checking for updates exposes available update state`() = runTest {
        val candidate = candidate(
            assetName = "Daemonitor-1.0.3-macos-arm64.zip",
            installMode = UpdateInstallMode.Automatic,
            role = UpdateArtifactRole.UpdatePackage,
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
        val candidate = candidate("Daemonitor-1.0.3-macos-arm64.zip")

        assertEquals(0, SettingsUiState().updateNotificationCount)
        assertEquals(0, SettingsUiState(updateState = UpdateUiState.Checking).updateNotificationCount)
        assertEquals(0, SettingsUiState(updateState = UpdateUiState.UpToDate("1.0.2")).updateNotificationCount)
        assertEquals(0, SettingsUiState(updateState = UpdateUiState.Failed("No network")).updateNotificationCount)
        assertEquals(1, SettingsUiState(updateState = UpdateUiState.Available(candidate)).updateNotificationCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `opening an available update stages automatic packages`() = runTest {
        val prepared = mutableListOf<UpdateCandidate>()
        val candidate = candidate(
            assetName = "Daemonitor-1.0.3-macos-arm64.zip",
            installMode = UpdateInstallMode.Automatic,
            role = UpdateArtifactRole.UpdatePackage,
        )
        val staged = StagedUpdate(
            candidate = candidate,
            artifactPath = Path.of("/tmp/artifact.zip"),
            payloadPath = Path.of("/tmp/Daemonitor.app"),
            installation = InstallationInfo(
                platform = DesktopPlatform.MACOS,
                architecture = CpuArchitecture.ARM64,
                kind = InstallationKind.MACOS_APP_BUNDLE,
                installRoot = Path.of("/Applications/Daemonitor.app"),
                relaunchCommand = listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"),
            ),
        )
        val vm = SettingsViewModel(
            updateInstaller = UpdateInstaller { update, progress ->
                progress(0.5)
                prepared += update
                staged
            },
            scope = this,
        )

        vm.openUpdate(candidate)
        advanceUntilIdle()

        assertEquals(listOf(candidate), prepared)
        assertEquals(UpdateUiState.ReadyToInstall(candidate, staged), vm.state.value.updateState)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `restart and update applies staged package then exits`() = runTest {
        val applied = mutableListOf<StagedUpdate>()
        var exited = 0
        val candidate = candidate(
            assetName = "Daemonitor-1.0.3-macos-arm64.zip",
            installMode = UpdateInstallMode.Automatic,
            role = UpdateArtifactRole.UpdatePackage,
        )
        val staged = StagedUpdate(
            candidate = candidate,
            artifactPath = Path.of("/tmp/artifact.zip"),
            payloadPath = Path.of("/tmp/Daemonitor.app"),
            installation = InstallationInfo(
                platform = DesktopPlatform.MACOS,
                architecture = CpuArchitecture.ARM64,
                kind = InstallationKind.MACOS_APP_BUNDLE,
                installRoot = Path.of("/Applications/Daemonitor.app"),
                relaunchCommand = listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"),
            ),
        )
        val vm = SettingsViewModel(
            initial = SettingsUiState(updateState = UpdateUiState.ReadyToInstall(candidate, staged)),
            updateApplier = UpdateApplier { applied += it },
            onExitForUpdate = { exited += 1 },
            scope = this,
        )

        vm.restartAndUpdate()

        assertEquals(listOf(staged), applied)
        assertEquals(1, exited)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `installer failures are surfaced in update state`() = runTest {
        val candidate = candidate("Daemonitor-1.0.3-windows-x64.msi")
        val vm = SettingsViewModel(
            updateInstaller = UpdateInstaller { _, _ -> error("No desktop") },
            scope = this,
        )

        vm.openUpdate(candidate)
        advanceUntilIdle()

        assertEquals(
            UpdateUiState.Failed("No desktop", candidate.releaseUrl),
            vm.state.value.updateState,
        )
    }

    private fun updateViewModel(
        result: UpdateCheckResult,
        scope: TestScope,
    ): SettingsViewModel = SettingsViewModel(
        updateChecker = { result },
        updateInstaller = UpdateInstaller { _, _ -> null },
        scope = scope,
    )

    private fun candidate(
        assetName: String,
        installMode: UpdateInstallMode = UpdateInstallMode.Manual,
        role: UpdateArtifactRole = UpdateArtifactRole.Installer,
    ): UpdateCandidate = UpdateCandidate(
        version = "1.0.3",
        releaseUrl = "https://example.com/release",
        assetName = assetName,
        downloadUrl = "https://example.com/$assetName",
        platform = DesktopPlatform.MACOS,
        architecture = CpuArchitecture.ARM64,
        role = role,
        installMode = installMode,
    )
}
