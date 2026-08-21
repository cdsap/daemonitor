package io.github.cdsap.daemonitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelaunchCommandTest {
    @Test
    fun `detects java executables across path separators`() {
        assertTrue(RelaunchCommand.isJavaExecutable("/opt/jdk/bin/java"))
        assertTrue(RelaunchCommand.isJavaExecutable("C:\\hosted\\jdk\\bin\\java.exe"))
        assertFalse(RelaunchCommand.isJavaExecutable("/Applications/Daemonitor.app/Contents/MacOS/Daemonitor"))
    }

    @Test
    fun `builds unix and windows java executable paths with trailing separators trimmed`() {
        assertEquals(
            "/opt/jdk/bin/java",
            RelaunchCommand.javaExecutablePath("/opt/jdk/", "Linux"),
        )
        assertEquals(
            "C:\\hosted\\jdk\\bin\\java.exe",
            RelaunchCommand.javaExecutablePath("C:\\hosted\\jdk\\", "Windows Server 2025"),
        )
    }

    @Test
    fun `extracts mac app bundle path when present`() {
        assertEquals(
            "/Applications/Daemonitor.app",
            RelaunchCommand.macAppBundlePath("/Applications/Daemonitor.app/Contents/MacOS/Daemonitor"),
        )
        assertNull(RelaunchCommand.macAppBundlePath("/opt/Daemonitor/bin/Daemonitor"))
    }

    @Test
    fun `source run relaunches the named desktop entry point`() {
        val command = RelaunchCommand.buildCommand(
            executable = "/opt/jdk/bin/java",
            javaHome = "/opt/jdk",
            classpath = "build/classes:kotlin-runtime.jar",
            osName = "Linux",
            options = RelaunchCommand.Options(),
        )

        assertEquals(
            listOf(
                "/opt/jdk/bin/java",
                "-cp",
                "build/classes:kotlin-runtime.jar",
                "io.github.cdsap.daemonitor.Daemonitor",
            ),
            command,
        )
    }

    @Test
    fun `source run uses windows java executable name on windows`() {
        val command = RelaunchCommand.buildCommand(
            executable = "C:\\hostedtoolcache\\windows\\Java_Temurin-Hotspot_jdk\\21\\x64\\bin\\java.exe",
            javaHome = "C:\\hostedtoolcache\\windows\\Java_Temurin-Hotspot_jdk\\21\\x64",
            classpath = "build/classes;kotlin-runtime.jar",
            osName = "Windows Server 2025",
            options = RelaunchCommand.Options(),
        )

        assertEquals(
            listOf(
                "C:\\hostedtoolcache\\windows\\Java_Temurin-Hotspot_jdk\\21\\x64\\bin\\java.exe",
                "-cp",
                "build/classes;kotlin-runtime.jar",
                "io.github.cdsap.daemonitor.Daemonitor",
            ),
            command,
        )
    }

    @Test
    fun `packaged non-mac application relaunches the current executable`() {
        val command = RelaunchCommand.buildCommand(
            executable = "/opt/Daemonitor/bin/Daemonitor",
            javaHome = "/unused/java",
            classpath = "/unused/classpath",
            osName = "Linux",
            options = RelaunchCommand.Options(),
        )

        assertEquals(listOf("/opt/Daemonitor/bin/Daemonitor"), command)
    }

    @Test
    fun `buildCommand applies mode options for packaged and classpath launches`() {
        val desktop = RelaunchCommand.buildCommand(
            executable = "/Applications/Daemonitor.app/Contents/MacOS/Daemonitor",
            javaHome = "/unused",
            classpath = "unused",
            osName = "Mac OS X",
            options = RelaunchCommand.Options(reopenMacDesktopBundle = true),
        )
        assertEquals(listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"), desktop)

        val headless = RelaunchCommand.buildCommand(
            executable = "/opt/jdk/bin/java",
            javaHome = "/opt/jdk",
            classpath = "app.jar",
            osName = "Linux",
            options = RelaunchCommand.Options(extraArgs = listOf("--headless")),
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
