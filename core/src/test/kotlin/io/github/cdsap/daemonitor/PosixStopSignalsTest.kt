package io.github.cdsap.daemonitor

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class PosixStopSignalsTest {
    @Test
    fun `ensureDeliverable is idempotent`() {
        PosixStopSignals.ensureDeliverable()
        PosixStopSignals.ensureDeliverable()
    }

    @Test
    fun `SIGINT reaches a JVM that inherited ignored stop signals`() {
        assumeTrue(!isWindows(), "POSIX signal disposition is Unix-specific")
        val javaHome = System.getProperty("java.home")
        val java = java.nio.file.Path.of(javaHome, "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val wrapper = Files.createTempFile("posix-stop-signals-", ".sh")
        try {
            wrapper.writeText(
                """
                #!/bin/sh
                trap '' INT QUIT
                exec "$java" -classpath "$classpath" io.github.cdsap.daemonitor.PosixStopSignalsProbe
                """.trimIndent() + "\n",
            )
            assertTrue(wrapper.toFile().setExecutable(true))
            val process = ProcessBuilder(wrapper.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            try {
                Thread.sleep(500L)
                assertTrue(process.isAlive)
                ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "probe should exit on SIGINT")
            } finally {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            Files.deleteIfExists(wrapper)
        }
    }
}

/** Minimal main used only by [PosixStopSignalsTest]. */
object PosixStopSignalsProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        PosixStopSignals.ensureDeliverable()
        Thread.sleep(60_000L)
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")
