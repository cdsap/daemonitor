package io.github.cdsap.daemonitor

import kotlin.test.Test
import kotlin.test.assertEquals

class HeadlessModeSwitcherTest {
    @Test
    fun `packaged application relaunches the current executable in headless mode`() {
        val command = HeadlessModeSwitcher.commandForCurrentProcess(
            executable = "/Applications/Daemonitor.app/Contents/MacOS/Daemonitor",
            javaHome = "/unused/java",
            classpath = "/unused/classpath",
            osName = "Mac OS X",
        )

        assertEquals(
            listOf("/Applications/Daemonitor.app/Contents/MacOS/Daemonitor", "--headless"),
            command,
        )
    }

    @Test
    fun `source run relaunches the named desktop entry point with headless flag`() {
        val command = HeadlessModeSwitcher.commandForCurrentProcess(
            executable = "/opt/jdk/bin/java",
            javaHome = "/opt/jdk",
            classpath = "build/classes:kotlin-runtime.jar",
            osName = "Linux",
        )

        assertEquals(
            listOf(
                "/opt/jdk/bin/java",
                "-cp",
                "build/classes:kotlin-runtime.jar",
                "io.github.cdsap.daemonitor.Daemonitor",
                "--headless",
            ),
            command,
        )
    }

    @Test
    fun `source run uses windows java executable name on windows`() {
        val command = HeadlessModeSwitcher.commandForCurrentProcess(
            executable = "C:\\hostedtoolcache\\windows\\Java_Temurin-Hotspot_jdk\\21\\x64\\bin\\java.exe",
            javaHome = "C:\\hostedtoolcache\\windows\\Java_Temurin-Hotspot_jdk\\21\\x64",
            classpath = "build/classes;kotlin-runtime.jar",
            osName = "Windows Server 2025",
        )

        assertEquals(
            listOf(
                "C:\\hostedtoolcache\\windows\\Java_Temurin-Hotspot_jdk\\21\\x64\\bin\\java.exe",
                "-cp",
                "build/classes;kotlin-runtime.jar",
                "io.github.cdsap.daemonitor.Daemonitor",
                "--headless",
            ),
            command,
        )
    }
}
