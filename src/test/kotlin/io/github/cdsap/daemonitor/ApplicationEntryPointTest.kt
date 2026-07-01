package io.github.cdsap.daemonitor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ApplicationEntryPointTest {
    @Test
    fun `application exposes a named static entry point`() {
        val entryPoint = Class.forName("io.github.cdsap.daemonitor.Daemonitor")
            .getMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isPublic(entryPoint.modifiers))
        assertTrue(Modifier.isStatic(entryPoint.modifiers))
    }

    @Test
    fun `headless mode exposes a named static entry point`() {
        val entryPoint = Class.forName("io.github.cdsap.daemonitor.DaemonitorHeadless")
            .getMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isPublic(entryPoint.modifiers))
        assertTrue(Modifier.isStatic(entryPoint.modifiers))
    }

    @Test
    fun `application content shows startup loading before service is ready`() = runComposeUiTest {
        setContent { DaemonitorContent(service = null) }

        onNodeWithText("Starting Daemonitor").assertExists()
        onNodeWithText("Preparing local watcher state...").assertExists()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }
}
