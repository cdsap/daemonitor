package io.github.cdsap.daemonitor

import kotlin.test.Test
import kotlin.test.assertEquals

class HeadlessMacModeTest {
    @Test
    fun `mac headless mode hides awt from the dock`() {
        val previousUiElement = System.getProperty("apple.awt.UIElement")
        val previousName = System.getProperty("apple.awt.application.name")
        try {
            System.clearProperty("apple.awt.UIElement")
            System.clearProperty("apple.awt.application.name")

            HeadlessMacMode.configure(osName = "Mac OS X")

            assertEquals("true", System.getProperty("apple.awt.UIElement"))
            assertEquals("Daemonitor", System.getProperty("apple.awt.application.name"))
        } finally {
            restoreProperty("apple.awt.UIElement", previousUiElement)
            restoreProperty("apple.awt.application.name", previousName)
        }
    }

    @Test
    fun `non mac headless mode leaves awt properties alone`() {
        val previousUiElement = System.getProperty("apple.awt.UIElement")
        val previousName = System.getProperty("apple.awt.application.name")
        try {
            System.clearProperty("apple.awt.UIElement")
            System.clearProperty("apple.awt.application.name")

            HeadlessMacMode.configure(osName = "Linux")

            assertEquals(null, System.getProperty("apple.awt.UIElement"))
            assertEquals(null, System.getProperty("apple.awt.application.name"))
        } finally {
            restoreProperty("apple.awt.UIElement", previousUiElement)
            restoreProperty("apple.awt.application.name", previousName)
        }
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }
}
