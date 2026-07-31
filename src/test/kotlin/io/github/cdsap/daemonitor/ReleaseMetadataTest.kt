package io.github.cdsap.daemonitor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseMetadataTest {
    @Test
    fun `metadata generator writes checksums and latest update contract`(@TempDir tempDir: Path) {
        val assetsDir = tempDir.resolve("assets").createDirectories()
        val outputDir = tempDir.resolve("metadata")

        val assets = mapOf(
            "Daemonitor-1.0.2-linux.deb" to "linux package",
            "Daemonitor-1.0.2-windows.msi" to "windows package",
            "Daemonitor-1.0.2-macos.dmg" to "macos package",
        )
        assets.forEach { (name, content) -> assetsDir.resolve(name).writeText(content) }

        val result = ProcessBuilder(
            bashExecutable(),
            "scripts/generate-release-metadata.sh",
            assetsDir.toString(),
            outputDir.toString(),
            "1.0.2",
            "v1.0.2",
            "cdsap/daemonitor",
        )
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, result.waitFor(), output)

        val checksums = outputDir.resolve("checksums.txt").readText()
        assets.keys.forEach { name ->
            val expected = sha256(assetsDir.resolve(name))
            assertTrue(checksums.contains("$expected  $name"), checksums)
        }

        val latest = outputDir.resolve("latest.json").readText()
        assertTrue(latest.contains("\"schemaVersion\": 1"), latest)
        assertTrue(latest.contains("\"version\": \"1.0.2\""), latest)
        assertTrue(latest.contains("\"tag\": \"v1.0.2\""), latest)
        assertTrue(latest.contains("\"platform\": \"linux\""), latest)
        assertTrue(latest.contains("\"platform\": \"windows\""), latest)
        assertTrue(latest.contains("\"platform\": \"macos\""), latest)
        assertTrue(
            latest.contains(
                "\"url\": \"https://github.com/cdsap/daemonitor/releases/download/v1.0.2/Daemonitor-1.0.2-macos.dmg\"",
            ),
            latest,
        )
        assertTrue(latest.contains("\"sha256\": \"${sha256(assetsDir.resolve("Daemonitor-1.0.2-linux.deb"))}\""), latest)

        assertEquals(latest, outputDir.resolve("update.json").readText())
    }

    @Test
    fun `release workflow publishes metadata after all native assets exist`() {
        val workflow = Path.of(".github/workflows/release.yml").readText()

        assertTrue(workflow.contains("Read package version"), workflow)
        assertTrue(workflow.contains("upload-artifact"), workflow)
        assertTrue(workflow.contains("download-artifact"), workflow)
        assertTrue(workflow.contains("scripts/generate-release-metadata.sh"), workflow)
        assertTrue(workflow.contains("release-metadata/latest.json"), workflow)
        assertTrue(workflow.contains("release-metadata/update.json"), workflow)
        assertTrue(workflow.contains("release-metadata/checksums.txt"), workflow)
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun bashExecutable(): String {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return "bash"
        }

        return listOf(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
        ).firstOrNull { Files.isExecutable(Path.of(it)) } ?: "bash"
    }
}
