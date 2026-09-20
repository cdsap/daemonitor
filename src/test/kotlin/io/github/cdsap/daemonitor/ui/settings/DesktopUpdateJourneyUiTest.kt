package io.github.cdsap.daemonitor.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import io.github.cdsap.daemonitor.application.platform.UrlOpener
import io.github.cdsap.daemonitor.application.update.ApplyUpdate
import io.github.cdsap.daemonitor.application.update.CheckForUpdate
import io.github.cdsap.daemonitor.application.update.PrepareUpdate
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.application.update.UpdateSource
import io.github.cdsap.daemonitor.update.CpuArchitecture
import io.github.cdsap.daemonitor.update.DesktopPlatform
import io.github.cdsap.daemonitor.update.InstallationInfo
import io.github.cdsap.daemonitor.update.InstallationKind
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateArtifactRole
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstallMode
import io.github.cdsap.daemonitor.update.UpdateInstaller
import io.github.cdsap.daemonitor.ui.common.AppScaffold
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop upgrade path: Settings badge → Updates card → Download → Restart and Update.
 *
 * Wires [AppScaffold] + [SettingsScreen] + [SettingsViewModel] the same way [io.github.cdsap.daemonitor.Main]
 * does, with a fake [UpdateService] so no network, disk, or process exit happens.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopUpdateJourneyUiTest {

    @Test
    fun `settings badge leads through download and restart to apply the staged update`() = runComposeUiTest {
        val candidate = automaticCandidate()
        val staged = stagedUpdate(candidate)
        val applied = mutableListOf<StagedUpdate>()
        var exited = 0

        val viewModel = SettingsViewModel(
            initial = SettingsUiState(updateState = UpdateUiState.Available(candidate)),
            updateService = UpdateService(
                checkForUpdate = CheckForUpdate(
                    source = UpdateSource { UpdateCheckResult.Available(candidate) },
                    currentVersion = { "1.0.2" },
                ),
                prepareUpdate = PrepareUpdate(
                    UpdateInstaller { update, onProgress ->
                        assertEquals(candidate, update)
                        onProgress(0.5)
                        staged
                    },
                ),
                applyUpdate = ApplyUpdate(
                    applier = UpdateApplier { applied += it },
                    processExiter = ProcessExiter { exited += 1 },
                ),
                urlOpener = UrlOpener {},
            ),
        )

        setContent {
            val settingsState by viewModel.state.collectAsState()
            WatcherTheme {
                AppScaffold(
                    settingsNotificationCount = settingsState.updateNotificationCount,
                    liveContent = { Text("Live content") },
                    historyContent = { Text("History content") },
                    settingsContent = {
                        SettingsScreen(
                            state = settingsState,
                            onRetentionDays = viewModel::setRetentionDays,
                            onCheckForUpdates = viewModel::checkForUpdates,
                            onOpenUpdate = viewModel::openUpdate,
                            onRestartAndUpdate = viewModel::restartAndUpdate,
                            onOpenManualDownload = viewModel::openManualDownload,
                        )
                    },
                )
            }
        }

        onNodeWithText("Settings (1)").assertExists().performClick()
        onNodeWithText("Application updates").assertExists()
        onNodeWithText("Download Update").assertExists().performClick()

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Restart and Update").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Daemonitor 1.0.3 is ready to install.").assertExists()
        onNodeWithText("Restart and Update").performClick()

        assertEquals(listOf(staged), applied)
        assertEquals(1, exited)
    }

    @Test
    fun `manual check from settings downloads and stages an available update`() = runComposeUiTest {
        val candidate = automaticCandidate()
        val staged = stagedUpdate(candidate)
        val prepared = mutableListOf<UpdateCandidate>()

        val viewModel = SettingsViewModel(
            updateService = UpdateService(
                checkForUpdate = CheckForUpdate(
                    source = UpdateSource { UpdateCheckResult.Available(candidate) },
                    currentVersion = { "1.0.2" },
                ),
                prepareUpdate = PrepareUpdate(
                    UpdateInstaller { update, onProgress ->
                        onProgress(1.0)
                        prepared += update
                        staged
                    },
                ),
                applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
                urlOpener = UrlOpener {},
            ),
        )

        setContent {
            val settingsState by viewModel.state.collectAsState()
            WatcherTheme {
                AppScaffold(
                    settingsNotificationCount = settingsState.updateNotificationCount,
                    liveContent = { Text("Live content") },
                    historyContent = { Text("History content") },
                    settingsContent = {
                        SettingsScreen(
                            state = settingsState,
                            onRetentionDays = viewModel::setRetentionDays,
                            onCheckForUpdates = viewModel::checkForUpdates,
                            onOpenUpdate = viewModel::openUpdate,
                            onRestartAndUpdate = viewModel::restartAndUpdate,
                        )
                    },
                )
            }
        }

        onNodeWithText("Settings").performClick()
        onNodeWithText("Check for updates").assertExists().performClick()

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Download Update").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Settings (1)").assertExists()
        onNodeWithText("Download Update").performClick()

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Restart and Update").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(candidate), prepared)
        onNodeWithText("Daemonitor 1.0.3 is ready to install.").assertExists()
    }

    private fun automaticCandidate(): UpdateCandidate = UpdateCandidate(
        version = "1.0.3",
        releaseUrl = "https://example.com/release",
        assetName = "Daemonitor-1.0.3-macos-arm64.zip",
        downloadUrl = "https://example.com/Daemonitor-1.0.3-macos-arm64.zip",
        platform = DesktopPlatform.MACOS,
        architecture = CpuArchitecture.ARM64,
        role = UpdateArtifactRole.UpdatePackage,
        installMode = UpdateInstallMode.Automatic,
    )

    private fun stagedUpdate(candidate: UpdateCandidate): StagedUpdate = StagedUpdate(
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
}
