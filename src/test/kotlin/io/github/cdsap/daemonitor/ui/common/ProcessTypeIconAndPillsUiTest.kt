package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProcessTypeIconUiTest {

    @Test
    fun `process type icons stay inside their square bounds`() = runComposeUiTest {
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                ProcessType.entries.forEach { type ->
                    ProcessTypeIcon(type, size = 16.dp)
                }
            }
        }

        ProcessType.entries.forEach { type ->
            onNodeWithTag("process-type-icon-${type.name}")
                .assertWidthIsEqualTo(16.dp)
                .assertHeightIsEqualTo(16.dp)
        }
    }
}

@OptIn(ExperimentalTestApi::class)
class PillsUiTest {

    @Test
    fun `status pill stays single line when column width is tight`() = runComposeUiTest {
        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK) {
                Box(Modifier.width(52.dp).testTag("pill-host")) {
                    StatusPill(FinalStatus.INTERRUPTED, Modifier.fillMaxWidth())
                }
            }
        }

        // Two wrapped lines would be well above a compact pill (~18–22dp / ~48–66px at test density).
        val pillHeightPx = onNodeWithTag("pill-interrupted").fetchSemanticsNode().size.height
        val hostHeightPx = onNodeWithTag("pill-host").fetchSemanticsNode().size.height
        assertTrue(pillHeightPx <= 66f, "pill height was $pillHeightPx")
        assertTrue(hostHeightPx <= 66f, "host height was $hostHeightPx")
    }
}
