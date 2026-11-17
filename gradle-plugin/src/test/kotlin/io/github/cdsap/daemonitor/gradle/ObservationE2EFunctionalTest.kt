package io.github.cdsap.daemonitor.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * End-to-end against a real daemonitor-cored: session window must span the build (not ~2 ms).
 *
 * Requires `-Pdaemonitor.cored.binary=/path/to/daemonitor-cored` (or skips).
 */
class ObservationE2EFunctionalTest {
    @Test
    fun `settings plugin observation window covers a multi-second build`() {
        val coredBinary = System.getProperty("daemonitor.cored.binary")?.takeIf { it.isNotBlank() }
            ?: run {
                println("SKIP ObservationE2EFunctionalTest: set -Pdaemonitor.cored.binary=...")
                return
            }
        assertTrue(Files.isExecutable(Path.of(coredBinary)), "cored binary not executable: $coredBinary")

        val work = Files.createTempDirectory("daemonitor-obs-e2e")
        val socket = work.resolve("cored.sock")
        val db = work.resolve("cored.sqlite")
        val project = work.resolve("project")
        Files.createDirectories(project)

        val cored =
            ProcessBuilder(
                coredBinary,
                "--socket",
                socket.toString(),
                "--db",
                db.toString(),
            ).redirectErrorStream(true)
                .directory(work.toFile())
                .start()

        try {
            waitForSocket(socket, cored)

            Files.writeString(
                project.resolve("settings.gradle.kts"),
                """
                plugins {
                    id("io.github.cdsap.daemonitor")
                }
                rootProject.name = "obs-e2e"
                """.trimIndent(),
            )
            Files.writeString(
                project.resolve("build.gradle.kts"),
                """
                tasks.register("hold") {
                    doLast {
                        Thread.sleep(2500)
                        println("held")
                    }
                }
                """.trimIndent(),
            )

            val result =
                GradleRunner.create()
                    .withProjectDir(project.toFile())
                    .withPluginClasspath()
                    .withArguments(
                        "hold",
                        "-Pdaemonitor.cored.socket=$socket",
                        "--stacktrace",
                        "--no-configuration-cache",
                    )
                    .forwardOutput()
                    .build()

            assertTrue(result.task(":hold")?.outcome == TaskOutcome.SUCCESS, result.output)
            val report = project.resolve("build/daemonitor/observation-summary.json")
            assertTrue(Files.isRegularFile(report), "missing report: $report\n${result.output}")
            val json = Files.readString(report)
            println("E2E observation-summary.json=$json")

            val opened = Regex("\"opened_at_ms\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
            val closed = Regex("\"closed_at_ms\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
            assertTrue(opened != null && closed != null, json)
            val durationMs = closed - opened
            // Regression for empty session-B overwrite: that window was ~2ms at end of build.
            assertTrue(
                durationMs >= 1500,
                "expected observation window >= 1500ms (not a ~2ms late recreate), was ${durationMs}ms: $json",
            )
            if (Regex("\"processes\"\\s*:\\s*\\[\\s*]").containsMatchIn(json)) {
                println("WARN: processes empty after ${durationMs}ms window — sampling/association separate from close-capture")
            } else {
                assertTrue(json.contains("\"pid\""), json)
            }
        } finally {
            cored.destroy()
            cored.waitFor(5, TimeUnit.SECONDS)
            deleteRecursively(work)
        }
    }

    private fun waitForSocket(socket: Path, process: Process) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                fail("cored exited early: ${process.inputStream.bufferedReader().readText()}")
            }
            if (Files.exists(socket)) return
            Thread.sleep(50)
        }
        fail("cored socket never appeared: $socket")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
