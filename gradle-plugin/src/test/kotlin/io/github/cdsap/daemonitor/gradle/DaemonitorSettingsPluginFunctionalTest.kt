package io.github.cdsap.daemonitor.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class DaemonitorSettingsPluginFunctionalTest {
    @Test
    fun `applies from settings gradle and keeps the build green without cored`() {
        val project = Files.createTempDirectory("daemonitor-settings-plugin-ft")
        try {
            Files.writeString(
                project.resolve("settings.gradle.kts"),
                """
                plugins {
                    id("io.github.cdsap.daemonitor")
                }
                rootProject.name = "demo"
                """.trimIndent(),
            )
            Files.writeString(
                project.resolve("build.gradle.kts"),
                """
                tasks.register("hello") {
                    doLast { println("daemonitor-hello") }
                }
                """.trimIndent(),
            )

            val result =
                GradleRunner.create()
                    .withProjectDir(project.toFile())
                    .withPluginClasspath()
                    .withArguments("hello", "--stacktrace")
                    .forwardOutput()
                    .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":hello")?.outcome)
            assertTrue(result.output.contains("daemonitor-hello"), result.output)
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
