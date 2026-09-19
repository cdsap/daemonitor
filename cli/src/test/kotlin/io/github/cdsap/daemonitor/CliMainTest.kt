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
        val usage = output.toString()
        assertTrue(usage.startsWith("Usage: daemonitor-cli [options]"))
        assertTrue(usage.contains("--no-color"))
        assertTrue(usage.contains("--collect-only"))
    }

    @Test
    fun `help formatting has no leading blank line and aligned options`() {
        val output = ByteArrayOutputStream()

        assertEquals(0, CliLauncher.run(arrayOf("--help"), output = PrintStream(output)))
        val usage = output.toString()
        val lines = usage.lines()

        assertEquals("Usage: daemonitor-cli [options]", lines.first())

        val optionLines = lines.filter { it.startsWith("  -") || it.startsWith("  --") }
        assertEquals(
            listOf(
                "  -h, --help       Show this help.",
                "  -v, --version    Show the Daemonitor version.",
                "  --plain          Disable colors and terminal screen clearing.",
                "  --no-color       Alias for --plain.",
                "  --collect-only   Collect and persist without rendering terminal output.",
                "  --db PATH        Store data in this SQLite database.",
                "  --poll-interval SECONDS",
                "  --retention DAYS",
            ),
            optionLines,
        )

        val descriptionColumns = listOf(
            optionLines[0].indexOf("Show"),
            optionLines[1].indexOf("Show"),
            optionLines[2].indexOf("Disable"),
            optionLines[3].indexOf("Alias"),
            optionLines[4].indexOf("Collect"),
            optionLines[5].indexOf("Store"),
        )
        assertEquals(setOf(19), descriptionColumns.toSet(), "option descriptions should share one alignment column")
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
}
