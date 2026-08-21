package io.github.cdsap.daemonitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLaunchCommandTest {
    @Test
    fun `detects java executables across path separators`() {
        assertTrue(AppLaunchCommand.isJavaExecutable("/opt/jdk/bin/java"))
        assertTrue(AppLaunchCommand.isJavaExecutable("C:\\hosted\\jdk\\bin\\java.exe"))
        assertFalse(AppLaunchCommand.isJavaExecutable("/Applications/Daemonitor.app/Contents/MacOS/Daemonitor"))
    }

    @Test
    fun `builds unix and windows java executable paths with trailing separators trimmed`() {
        assertEquals(
            "/opt/jdk/bin/java",
            AppLaunchCommand.javaExecutablePath("/opt/jdk/", "Linux"),
        )
        assertEquals(
            "C:\\hosted\\jdk\\bin\\java.exe",
            AppLaunchCommand.javaExecutablePath("C:\\hosted\\jdk\\", "Windows Server 2025"),
        )
    }

    @Test
    fun `extracts mac app bundle path when present`() {
        assertEquals(
            "/Applications/Daemonitor.app",
            AppLaunchCommand.macAppBundlePath("/Applications/Daemonitor.app/Contents/MacOS/Daemonitor"),
        )
        assertNull(AppLaunchCommand.macAppBundlePath("/opt/Daemonitor/bin/Daemonitor"))
    }

    @Test
    fun `buildCommand applies mode options for packaged and classpath launches`() {
        val desktop = AppLaunchCommand.buildCommand(
            executable = "/Applications/Daemonitor.app/Contents/MacOS/Daemonitor",
            javaHome = "/unused",
            classpath = "unused",
            osName = "Mac OS X",
            options = AppLaunchCommand.Options(reopenMacDesktopBundle = true),
        )
        assertEquals(listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"), desktop)

        val headless = AppLaunchCommand.buildCommand(
            executable = "/opt/jdk/bin/java",
            javaHome = "/opt/jdk",
            classpath = "app.jar",
            osName = "Linux",
            options = AppLaunchCommand.Options(extraArgs = listOf("--headless")),
        )
        assertEquals(
            listOf(
                "/opt/jdk/bin/java",
                "-cp",
                "app.jar",
                "io.github.cdsap.daemonitor.Daemonitor",
                "--headless",
            ),
            headless,
        )
    }
}
