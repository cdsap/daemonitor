package io.github.cdsap.daemonitor.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ThemeUiTest {

    @Test
    fun `explicit appearance overrides system appearance`() = runComposeUiTest {
        var actual = Color.Unspecified

        setContent {
            WatcherTheme(appearance = AppearancePreference.DARK, systemDarkTheme = false) {
                val background = MaterialTheme.colorScheme.background
                SideEffect { actual = background }
            }
        }

        waitForIdle()
        assertEquals(WatcherDarkColors.background, actual)
    }

    @Test
    fun `system appearance follows detected dark mode`() = runComposeUiTest {
        var actual = Color.Unspecified

        setContent {
            WatcherTheme(appearance = AppearancePreference.SYSTEM, systemDarkTheme = true) {
                val background = MaterialTheme.colorScheme.background
                SideEffect { actual = background }
            }
        }

        waitForIdle()
        assertEquals(WatcherDarkColors.background, actual)
    }
}
