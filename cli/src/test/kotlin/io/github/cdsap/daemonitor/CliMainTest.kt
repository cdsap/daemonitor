package io.github.cdsap.daemonitor

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliMainTest {
    @Test
    fun `help does not initialize the monitoring runtime`() {
        val output = ByteArrayOutputStream()

        val exitCode = CliLauncher.run(
            args = arrayOf("--help"),
            output = PrintStream(output),
        )

        assertEquals(0, exitCode)
        assertTrue(output.toString().contains("Usage: daemonitor-cli [options]"))
        assertTrue(output.toString().contains("--no-color"))
        assertTrue(output.toString().contains("--collect-only"))
    }

    @Test
    fun `version prints build version without starting monitor`() {
        val output = ByteArrayOutputStream()

        val exitCode = CliLauncher.run(
            args = arrayOf("--version"),
            output = PrintStream(output),
        )

        assertEquals(0, exitCode)
        assertEquals(BuildInfo.current.version, output.toString().trim())
    }

    @Test
    fun `unknown options fail with usage guidance`() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()

        val exitCode = CliLauncher.run(
            args = arrayOf("--wat"),
            output = PrintStream(output),
            error = PrintStream(error),
        )

        assertEquals(2, exitCode)
        assertTrue(error.toString().contains("Unknown option: --wat"))
        assertTrue(output.toString().contains("Use --help for usage."))
    }

    @Test
    fun `configuration options are documented`() {
        val output = ByteArrayOutputStream()

        assertEquals(0, CliLauncher.run(arrayOf("--help"), output = PrintStream(output)))
        val usage = output.toString()
        assertTrue(usage.contains("--db PATH"))
        assertTrue(usage.contains("--poll-interval SECONDS"))
        assertTrue(usage.contains("--retention DAYS"))
        assertTrue(usage.contains("1-90"))
    }

    @Test
    fun `invalid configuration values fail before starting the runtime`() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()

        val exitCode = CliLauncher.run(
            args = arrayOf("--poll-interval", "zero"),
            output = PrintStream(output),
            error = PrintStream(error),
        )

        assertEquals(2, exitCode)
        assertTrue(error.toString().contains("Invalid value for --poll-interval"))
    }

    @Test
    fun `out-of-range retention values fail before starting the runtime`() {
        for (value in listOf("0", "999")) {
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()

            val exitCode = CliLauncher.run(
                args = arrayOf("--retention", value),
                output = PrintStream(output),
                error = PrintStream(error),
            )

            assertEquals(2, exitCode, "expected non-zero exit for --retention $value")
            assertTrue(
                error.toString().contains("Invalid value for --retention"),
                "expected invalid-value message for --retention $value",
            )
            assertTrue(output.toString().contains("Use --help for usage."))
        }
    }
}
