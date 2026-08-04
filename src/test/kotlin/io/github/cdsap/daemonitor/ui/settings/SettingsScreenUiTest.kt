package io.github.cdsap.daemonitor.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import io.github.cdsap.daemonitor.ui.common.WatcherDarkColors
import io.github.cdsap.daemonitor.ui.common.WatcherLightColors
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {

    @Test
    fun `clicking a retention preset fires the callback with that value`() = runComposeUiTest {
        var picked: Long? = null
        setContent {
            WatcherTheme {
                SettingsScreen(SettingsUiState(retentionDays = 15), onRetentionDays = { picked = it })
            }
        }
        onNodeWithText("7 days").performClick()
        assertEquals(7L, picked)
    }

    @Test
    fun `the retention card renders`() = runComposeUiTest {
        setContent {
            WatcherTheme {
                SettingsScreen(SettingsUiState(retentionDays = 30), onRetentionDays = {})
            }
        }
        onNodeWithText("Keep build & process history for").assertExists()
    }

    @Test
    fun `appearance control offers all modes and applies dark theme immediately`() = runComposeUiTest {
        var picked: AppearancePreference? = null
        var renderedBackground = Color.Unspecified
        setContent {
            var appearance by remember { mutableStateOf(AppearancePreference.LIGHT) }
            WatcherTheme(appearance = appearance) {
                val background = MaterialTheme.colorScheme.background
                SideEffect { renderedBackground = background }
                SettingsScreen(
                    SettingsUiState(appearance = appearance),
                    onRetentionDays = {},
                    onAppearance = { picked = it; appearance = it },
                )
            }
        }

        waitForIdle()
        assertEquals(WatcherLightColors.background, renderedBackground)
        onNodeWithText("System").assertExists()
        onNodeWithText("Light").assertExists()
        onNodeWithText("Dark").assertExists().performClick()
        waitForIdle()
        onNodeWithText("Keep build & process history for").assertExists()
        assertEquals(AppearancePreference.DARK, picked)
        assertEquals(WatcherDarkColors.background, renderedBackground)
    }

    @Test
    fun `update card can trigger a manual check`() = runComposeUiTest {
        var checks = 0
        setContent {
            WatcherTheme {
                SettingsScreen(
                    SettingsUiState(),
                    onRetentionDays = {},
                    onCheckForUpdates = { checks += 1 },
                )
            }
        }

        onNodeWithText("Check for updates").performClick()

        assertEquals(1, checks)
    }

    @Test
    fun `available update renders download action`() = runComposeUiTest {
        var opened: UpdateCandidate? = null
        val candidate = UpdateCandidate(
            version = "1.0.3",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.3-macos.dmg",
            downloadUrl = "https://example.com/Daemonitor-1.0.3-macos.dmg",
        )
        setContent {
            WatcherTheme {
                SettingsScreen(
                    SettingsUiState(updateState = UpdateUiState.Available(candidate)),
                    onRetentionDays = {},
                    onOpenUpdate = { opened = it },
                )
            }
        }

        onNodeWithText("Download and open").performClick()

        assertEquals(candidate, opened)
    }

    @Test
    fun `downloading update renders progress`() = runComposeUiTest {
        val candidate = UpdateCandidate(
            version = "1.0.3",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.3-macos.dmg",
            downloadUrl = "https://example.com/Daemonitor-1.0.3-macos.dmg",
        )
        setContent {
            WatcherTheme {
                SettingsScreen(
                    SettingsUiState(updateState = UpdateUiState.Downloading(candidate, 0.42)),
                    onRetentionDays = {},
                )
            }
        }

        onNodeWithText("Downloading Daemonitor-1.0.3-macos.dmg: 42%.").assertExists()
    }
}
