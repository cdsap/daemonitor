package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmHeapCollectionDocTest {
    private val documentPath = Path.of("docs/jvm-heap-collection.md")
    private val document = Files.readString(documentPath)
    private val buildFile = Files.readString(Path.of("build.gradle.kts"))

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
        assertTrue(buildFile.contains("jdk.attach"), buildFile)
        assertTrue(buildFile.contains("java.management"), buildFile)
        assertTrue(buildFile.contains("jdk.management.agent"), buildFile)
    }
}
