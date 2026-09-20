package io.github.cdsap.daemonitor

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

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
    fun `SIGINT stops CLI even when the parent left stop signals ignored`() {
        assumeTrue(!isWindows(), "POSIX SIGINT launcher behavior is Unix-specific")
        val javaHome = System.getProperty("java.home")
        val java = java.nio.file.Path.of(javaHome, "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val db = Files.createTempDirectory("daemonitor-cli-sigint-").resolve("monitor.db")
        val stderr = Files.createTempFile("daemonitor-cli-sigint-err-", ".txt")
        val wrapper = Files.createTempFile("daemonitor-cli-wrap-", ".sh")
        try {
            // Job-control shells leave INT/QUIT ignored across exec; PosixStopSignals fixes that.
            wrapper.writeText(
                """
                #!/bin/sh
                trap '' INT QUIT
                exec "$java" -classpath "$classpath" io.github.cdsap.daemonitor.DaemonitorCli \
                  --plain --collect-only --db "$db" --poll-interval 1
                """.trimIndent() + "\n",
            )
            assertTrue(wrapper.toFile().setExecutable(true))

            val process = ProcessBuilder(wrapper.toString())
                .redirectError(stderr.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            try {
                assertTrue(process.isAlive, "CLI process should start")
                Thread.sleep(1_500L)

                val kill = ProcessBuilder("kill", "-INT", process.pid().toString()).start()
                assertEquals(0, kill.waitFor())
                val exited = process.waitFor(8, TimeUnit.SECONDS)
                assertTrue(exited, "CLI should exit after SIGINT without requiring SIGKILL")
                assertFalse(process.isAlive)
                val err = stderr.readText()
                assertFalse(
                    err.contains("ClosedByInterruptException"),
                    "interrupt during shutdown must not be reported as a poll failure: $err",
                )
            } finally {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            Files.deleteIfExists(wrapper)
            Files.deleteIfExists(stderr)
            Files.deleteIfExists(db)
            runCatching { Files.deleteIfExists(db.parent) }
        }
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")
