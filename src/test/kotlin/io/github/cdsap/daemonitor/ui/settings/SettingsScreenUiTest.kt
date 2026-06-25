package io.github.cdsap.daemonitor.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
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
}
