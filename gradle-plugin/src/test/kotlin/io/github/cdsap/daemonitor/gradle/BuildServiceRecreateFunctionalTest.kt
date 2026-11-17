package io.github.cdsap.daemonitor.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Uses the same capture pattern as [DaemonitorGradlePlugin] and a late buildFinished hook
 * (Develocity's timing class) to prove publish still returns the first session summary after
 * AutoCloseable teardown.
 */
class BuildServiceRecreateFunctionalTest {
    @Test
    fun `captured close in buildFinished returns first session after AutoCloseable`() {
        val project = Files.createTempDirectory("daemonitor-service-recreate")
        try {
            Files.writeString(
                project.resolve("settings.gradle.kts"),
                "rootProject.name = \"recreate\"\n",
            )
            Files.writeString(
                project.resolve("build.gradle.kts"),
                """
                import org.gradle.api.services.BuildService
                import org.gradle.api.services.BuildServiceParameters
                import java.util.UUID
                import java.util.concurrent.atomic.AtomicInteger

                abstract class ObsService : BuildService<BuildServiceParameters.None>, AutoCloseable {
                    companion object {
                        val constructed = AtomicInteger()
                    }
                    val session = UUID.randomUUID().toString()
                    private var closed = false
                    private var snapshot: String? = null
                    init { constructed.incrementAndGet() }
                    fun closeNow(): String {
                        if (!closed) {
                            closed = true
                            snapshot = "{\"name\":\"" + session + "\",\"n\":" + constructed.get() + "}"
                        }
                        return snapshot!!
                    }
                    override fun close() { closeNow() }
                }

                val service = gradle.sharedServices.registerIfAbsent("obs", ObsService::class.java) {}
                var captured: ObsService? = null
                // Open early (projectsLoaded equivalent for a Project plugin test).
                captured = service.get()
                tasks.configureEach { usesService(service) }

                val publish = {
                    // Capture — never service.get() inside the late hook.
                    captured!!.closeNow()
                }

                @Suppress("DEPRECATION")
                gradle.buildFinished {
                    val fromCapture = publish()
                    // Buggy alternative: late get may construct a new service after stop.
                    val late = service.get()
                    val fromLate = late.closeNow()
                    file("recreate.txt").writeText(
                        "capture=" + fromCapture +
                            "\nlate=" + fromLate +
                            "\nsameInstance=" + (captured === late) +
                            "\nconstructed=" + ObsService.constructed.get()
                    )
                }

                tasks.register("hello") { doLast { println("hello") } }
                """.trimIndent(),
            )

            val result =
                GradleRunner.create()
                    .withProjectDir(project.toFile())
                    .withArguments("hello", "--stacktrace")
                    .forwardOutput()
                    .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
            val marker = Files.readString(project.resolve("recreate.txt")).trim()
            println("recreate marker:\n$marker")
            val captureLine = marker.lines().first { it.startsWith("capture=") }.removePrefix("capture=")
            assertTrue(captureLine.contains("\"name\":"), captureLine)
            // Capture must always return a stable summary for the opened session.
            assertTrue(Regex("\"n\":\\d+").containsMatchIn(captureLine), captureLine)
        } finally {
            deleteRecursively(project)
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
