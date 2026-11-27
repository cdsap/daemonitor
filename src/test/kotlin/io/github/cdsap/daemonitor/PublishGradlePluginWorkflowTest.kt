package io.github.cdsap.daemonitor

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PublishGradlePluginWorkflowTest {
    private val workflow = Path.of(".github/workflows/publish-gradle-plugin.yml").readText()
    private val steps = workflow.split(Regex("(?m)^      - name: ")).drop(1)

    @Test
    fun `dispatch ref is validated before checkout and credentials`() {
        assertEquals("Validate publication ref", steps.first().lineSequence().first())
        assertTrue(workflow.contains("      INPUT_REF: \${{ inputs.ref }}"))
        steps.forEach { step ->
            assertFalse(script(step).contains("\${{ inputs."), "Input expression in shell script: $step")
        }
    }

    @Test
    fun `validation accepts only version tags`(@TempDir tempDir: Path) {
        val validation = script(step("Validate publication ref"))
        listOf("v0.0.0", "v1.3.3", "v12.34.567").forEach { ref ->
            assertEquals(0, runScript(validation, ref, tempDir).first, ref)
        }
        listOf(
            "", "main", "refs/tags/v1.3.3", "1.3.3", "v1.3", "v1x3x3", "v1.3.3-rc1",
            " v1.3.3", "v1.3.3 ", "v1.3.3\necho injected", "v1.3.3\n", "-n",
            "v1.3.3\$(touch injected)", "v1.3.3`touch injected`", "v1.3.3\"; touch injected; #",
        ).forEach { ref ->
            val (exitCode, output) = runScript(validation, ref, tempDir)
            assertTrue(exitCode != 0, "Validation accepted: $ref\n$output")
            assertFalse(tempDir.resolve("injected").exists(), "Executed input: $ref")
        }
    }

    @Test
    fun `publication logging reads ref from the environment`() {
        assertTrue(script(step("Reject SNAPSHOT plugin versions")).contains("from \$INPUT_REF\""))
        assertTrue(script(step("Summarize")).contains("from \$INPUT_REF\""))
    }

    @Test
    fun `summary treats shell metacharacters as literal data`(@TempDir tempDir: Path) {
        val ref = "v1.3.3\$(touch injected)`touch injected`\"; touch injected; #"
        val (exitCode, output) = runScript(script(step("Summarize")), ref, tempDir)
        assertEquals(0, exitCode, output)
        assertFalse(tempDir.resolve("injected").exists())
        assertTrue(tempDir.resolve("summary").readText().contains("from $ref"))
    }

    private fun step(name: String): String = steps.first { it.lineSequence().first() == name }

    // Extract literal run blocks from this workflow's steps without adding a YAML dependency.
    private fun script(step: String): String = step.lineSequence()
        .dropWhile { it != "        run: |" }
        .drop(1)
        .takeWhile { it.isBlank() || it.startsWith("          ") }
        .joinToString("\n") { it.removePrefix("          ") }

    private fun runScript(script: String, ref: String, directory: Path): Pair<Int, String> {
        val bash = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("C:\\Program Files\\Git\\bin\\bash.exe", "C:\\Program Files\\Git\\usr\\bin\\bash.exe")
                .firstOrNull { Path.of(it).exists() } ?: "bash"
        } else {
            "bash"
        }
        val process = ProcessBuilder(bash, "-c", script)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["INPUT_REF"] = ref
                environment()["PLUGIN_VERSION"] = "0.1.2"
                environment()["GITHUB_STEP_SUMMARY"] = directory.resolve("summary").toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
}
