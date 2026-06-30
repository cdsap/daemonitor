package io.github.cdsap.daemonitor

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessLauncherTest {
    @Test
    fun `help prints usage without starting the collector`() {
        val output = ByteArrayOutputStream()

        val exitCode = HeadlessLauncher.run(arrayOf("--help"), output = PrintStream(output))

        assertEquals(0, exitCode)
        assertTrue(output.toString().contains("daemonitor --headless"))
    }

    @Test
    fun `unknown option returns usage error without starting the collector`() {
        val error = ByteArrayOutputStream()

        val exitCode = HeadlessLauncher.run(arrayOf("--unknown"), error = PrintStream(error))

        assertEquals(2, exitCode)
        assertTrue(error.toString().contains("Unknown headless option: --unknown"))
    }
}
