package io.github.cdsap.daemonitor

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class PosixStopSignalsTest {
    @Test
    fun `ensureDeliverable is idempotent`() {
        PosixStopSignals.ensureDeliverable()
        PosixStopSignals.ensureDeliverable()
        assertTrue(PosixStopSignals.isInstalled() || isWindows())
    }

    @Test
    fun `SIGINT reaches a JVM that inherited ignored stop signals`() {
        assumeTrue(!isWindows(), "POSIX signal disposition is Unix-specific")
        val javaHome = System.getProperty("java.home")
        val java = java.nio.file.Path.of(javaHome, "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val ready = Files.createTempFile("posix-stop-ready-", ".flag")
        val wrapper = Files.createTempFile("posix-stop-signals-", ".sh")
        Files.deleteIfExists(ready)
        try {
            wrapper.writeText(
                """
                #!/bin/sh
                trap '' INT QUIT
                exec "$java" -classpath "$classpath" \
                  io.github.cdsap.daemonitor.PosixStopSignalsProbe \
                  "${ready.toAbsolutePath()}"
                """.trimIndent() + "\n",
            )
            assertTrue(wrapper.toFile().setExecutable(true))
            val process = ProcessBuilder(wrapper.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            try {
                assertTrue(waitForFile(ready, 10_000L), "probe did not become ready")
                assertTrue(process.isAlive)
                // Signal the process group leader (same PID after exec) a few times — CI runners
                // can delay handler registration under load.
                repeat(3) { attempt ->
                    if (!process.isAlive) return@repeat
                    ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor()
                    if (process.waitFor(2, TimeUnit.SECONDS)) return@repeat
                    Thread.sleep(200L * (attempt + 1))
                }
                assertTrue(
                    !process.isAlive || process.waitFor(3, TimeUnit.SECONDS),
                    "probe should exit on SIGINT (alive=${process.isAlive})",
                )
            } finally {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            Files.deleteIfExists(wrapper)
            Files.deleteIfExists(ready)
        }
    }
}

/** Minimal main used only by [PosixStopSignalsTest]. */
object PosixStopSignalsProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        PosixStopSignals.ensureDeliverable()
        check(PosixStopSignals.isInstalled()) { "PosixStopSignals failed to install a SIGINT handler" }
        args.firstOrNull()?.let { readyPath ->
            java.nio.file.Path.of(readyPath).toFile().writeText("ready")
        }
        Thread.sleep(60_000L)
    }
}

private fun waitForFile(path: java.nio.file.Path, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (Files.exists(path) && Files.size(path) > 0L) return true
        Thread.sleep(50L)
    }
    return Files.exists(path) && Files.size(path) > 0L
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")
