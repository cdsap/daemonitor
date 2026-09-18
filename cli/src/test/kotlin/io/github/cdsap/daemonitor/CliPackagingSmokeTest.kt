package io.github.cdsap.daemonitor

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CliPackagingSmokeTest {
    @Test
    fun `installed CLI collect-only run prints no SLF4J provider warnings`(@TempDir tempDir: Path) {
        val cliHome = Path.of(System.getProperty("daemonitor.cli.home"))
        val scriptName = if (isWindows()) "daemonitor-cli.bat" else "daemonitor-cli"
        val script = cliHome.resolve("bin").resolve(scriptName)
        assertTrue(script.exists(), "Missing installed CLI script at $script")
        if (!isWindows()) {
            assertTrue(script.isExecutable(), "CLI script is not executable: $script")
        }

        val db = tempDir.resolve("smoke.db")
        val stdoutFile = tempDir.resolve("stdout.txt").toFile()
        val stderrFile = tempDir.resolve("stderr.txt").toFile()
        val command = buildList {
            if (isWindows()) {
                add("cmd.exe")
                add("/c")
            }
            add(script.toAbsolutePath().toString())
            add("--collect-only")
            add("--db")
            add(db.toAbsolutePath().toString())
            add("--poll-interval")
            add("1")
        }

        val process = ProcessBuilder(command)
            .redirectOutput(stdoutFile)
            .redirectError(stderrFile)
            .start()
        // Give OSHI/SLF4J time to initialize on the first poll, then stop.
        Thread.sleep(1_500)
        process.destroy()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            fail("CLI process did not exit after destroy")
        }

        val combined = stdoutFile.toPath().readText() + stderrFile.toPath().readText()
        assertFalse(combined.contains("No SLF4J providers were found"), combined)
        assertFalse(combined.contains("Defaulting to no-operation (NOP) logger implementation"), combined)
        assertFalse(combined.contains("https://www.slf4j.org/codes.html#noProviders"), combined)
        assertFalse(combined.contains("SLF4J(W)"), combined)
    }

    @Test
    fun `poll failure messages remain a dedicated System_err channel`() {
        // Packaging must not redirect or swallow Daemonitor errors when fixing SLF4J noise.
        val source = Path.of("src/main/kotlin/io/github/cdsap/daemonitor/CliMain.kt").toFile().readText()
        assertTrue(source.contains("error.println(\"Daemonitor poll failed: \$pollError\")"), source)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
