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
}
