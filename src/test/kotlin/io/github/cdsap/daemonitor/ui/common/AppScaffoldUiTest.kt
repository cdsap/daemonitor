package io.github.cdsap.daemonitor.ui.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.BuildInfo
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppScaffoldUiTest {
    @Test
    fun `header shows the Daemonitor logo`() = runComposeUiTest {
        setContent {
            WatcherTheme {
                AppScaffold(
                    liveContent = { Text("Live") },
                    historyContent = { Text("History") },
                    settingsContent = { Text("Settings") },
                )
            }
        }

        onNodeWithContentDescription("Daemonitor logo").assertExists()
    }

    @Test
    fun `header shows application version and commit`() = runComposeUiTest {
        setContent {
            WatcherTheme {
                AppScaffold(
                    buildInfo = BuildInfo(version = "1.2.3", commit = "abc1234"),
                    liveContent = { Text("Live") },
                    historyContent = { Text("History") },
                    settingsContent = { Text("Settings") },
                )
            }
        }

        onNodeWithText("v1.2.3 · abc1234").assertExists()
    }

    @Test
    fun `segmented navigation switches between application screens`() = runComposeUiTest {
        setContent {
            WatcherTheme {
                AppScaffold(
                    liveContent = { Text("Live content") },
                    historyContent = { Text("History content") },
                    settingsContent = { Text("Settings content") },
                )
            }
        }

        onNodeWithText("Live content").assertExists()
        onNodeWithText("History").performClick()
        onNodeWithText("History content").assertExists()
        onNodeWithText("Settings").performClick()
        onNodeWithText("Settings content").assertExists()
    }
}
