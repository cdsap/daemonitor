package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmHeapCollectionDocTest {
    private val documentPath = Path.of("docs/jvm-heap-collection.md")
    private val document = Files.readString(documentPath)
    private val buildFile = Files.readString(Path.of("build.gradle.kts"))
    private val smokeScriptPath = Path.of("scripts/smoke-test-native-distribution.sh")
    private val smokeScript = Files.readString(smokeScriptPath)

    @Test
    fun `heap collection doc covers overhead and failure behavior`() {
        assertTrue(Files.isRegularFile(documentPath), "$documentPath should be checked in")
        listOf(
            "RSS",
            "Heap limit",
            "-Xmx",
            "Live heap",
            "Attach",
            "unavailable",
            "## Overhead",
            "## Failure behavior",
            "jdk.attach",
        ).forEach { required ->
            assertTrue(document.contains(required), "$documentPath should include: $required")
        }
    }

    @Test
    fun `native packaging includes attach and management modules`() {
        val nativeModules = buildFile
            .substringAfter("nativeDistributions")
            .substringAfter("modules(")
            .lineSequence()
            .takeWhile { !it.trimStart().startsWith(")") }
            .joinToString("\n")
        listOf("jdk.attach", "java.management", "jdk.management.agent").forEach { module ->
            assertTrue(nativeModules.contains("\"$module\""), nativeModules)
        }
    }

    @Test
    fun `native smoke test verifies packaged runtime includes jdk_attach and heap columns`() {
        assertTrue(Files.isRegularFile(smokeScriptPath), "$smokeScriptPath should be checked in")
        assertTrue(Files.isExecutable(smokeScriptPath), "$smokeScriptPath should be executable")
        listOf(
            "jdk.attach",
            "HEAP USED",
            "HEAP CMT",
            "HEAP LIMIT",
            "DAEMONITOR — HEADLESS",
            "--headless --help",
        ).forEach { required ->
            assertTrue(smokeScript.contains(required), "$smokeScriptPath should include: $required")
        }

        listOf(
            Path.of(".github/workflows/ci.yml"),
            Path.of(".github/workflows/release.yml"),
        ).forEach { workflowPath ->
            val workflow = Files.readString(workflowPath)
            assertTrue(
                workflow.contains("scripts/smoke-test-native-distribution.sh"),
                "$workflowPath should invoke the native distribution smoke test",
            )
            assertTrue(
                workflow.contains("\${{ matrix.app_path }}"),
                "$workflowPath should pass the platform app image path",
            )
        }
    }
}
