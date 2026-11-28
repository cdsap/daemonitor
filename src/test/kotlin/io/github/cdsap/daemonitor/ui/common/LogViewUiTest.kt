package io.github.cdsap.daemonitor.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LogViewUiTest {
    @Test
    fun `duplicate lines have distinct keys and content updates at the same size`() = runComposeUiTest {
        var lines by mutableStateOf(listOf("repeat", "repeat", "old"))
        setContent { WatcherTheme { LogView(lines, autoScroll = false) } }
        onAllNodesWithText("repeat").assertCountEquals(2)
        runOnIdle { lines = listOf("repeat", "repeat", "new") }
        onAllNodesWithText("repeat").assertCountEquals(2)
        onNodeWithText("new").assertExists()
        onNodeWithText("old").assertDoesNotExist()
    }
}
