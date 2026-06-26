package io.github.cdsap.daemonitor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppIconTest {
    @Test
    fun `runtime app icon is available on the classpath`() {
        val icon = javaClass.classLoader.getResource("icon/daemonitor.png")

        assertNotNull(icon)
    }

    @Test
    fun `native package icons are present for every supported platform`() {
        listOf(
            Path.of("icons/daemonitor.icns"),
            Path.of("icons/daemonitor.ico"),
            Path.of("icons/daemonitor.png"),
        ).forEach { icon ->
            assertTrue(Files.isRegularFile(icon), "$icon should exist")
            assertTrue(Files.size(icon) > 0L, "$icon should not be empty")
        }
    }
}
