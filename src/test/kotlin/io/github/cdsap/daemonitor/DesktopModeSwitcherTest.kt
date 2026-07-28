package io.github.cdsap.daemonitor

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModeSwitcherTest {
    @Test
    fun `packaged application relaunches the current executable without headless mode`() {
        val command = DesktopModeSwitcher.commandForCurrentProcess(
            executable = "/Applications/Daemonitor.app/Contents/MacOS/Daemonitor",
            javaHome = "/unused/java",
            classpath = "/unused/classpath",
            osName = "Mac OS X",
        )

        assertEquals(listOf("/Applications/Daemonitor.app/Contents/MacOS/Daemonitor"), command)
    }

    @Test
    fun `source run relaunches the named desktop entry point`() {
        val command = DesktopModeSwitcher.commandForCurrentProcess(
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
            ),
            command,
        )
    }
}
