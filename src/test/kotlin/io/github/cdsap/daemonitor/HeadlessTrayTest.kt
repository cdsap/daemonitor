package io.github.cdsap.daemonitor

import java.awt.Image
import java.awt.TrayIcon
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HeadlessTrayTest {
    @Test
    fun `unsupported tray environment does not fail headless startup`() {
        val error = ByteArrayOutputStream()

        val handle = HeadlessTray.install(
            onQuit = {},
            error = PrintStream(error),
            environment = UnsupportedTrayEnvironment,
        )

        assertSame(NoHeadlessTrayHandle, handle)
        assertEquals("", error.toString())
    }

    private object UnsupportedTrayEnvironment : TrayEnvironment {
        override fun isSupported(): Boolean = false
        override fun loadImage(resourcePath: String): Image = error("should not load image")
        override fun add(icon: TrayIcon) = error("should not add icon")
        override fun remove(icon: TrayIcon) = error("should not remove icon")
    }
}
