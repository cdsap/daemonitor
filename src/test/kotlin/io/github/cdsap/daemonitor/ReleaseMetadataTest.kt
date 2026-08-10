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
    fun `Gradle project and native package versions are aligned for v1_0_6`() {
        val buildFile = Path.of("build.gradle.kts").readText()

        assertTrue(buildFile.contains("version = \"1.0.6\""), buildFile)
        assertTrue(buildFile.contains("val nativePackageVersion = \"1.0.6\""), buildFile)
    }

    @Test
    fun `metadata generator writes checksums and latest update contract`(@TempDir tempDir: Path) {
        val assetsDir = tempDir.resolve("assets").createDirectories()
        val outputDir = tempDir.resolve("metadata")

        val assets = mapOf(
            "Daemonitor-1.0.7-linux-x64.deb" to "linux package",
            "Daemonitor-1.0.7-linux-x64.tar.gz" to "linux update",
            "Daemonitor-1.0.7-windows-x64.msi" to "windows package",
            "Daemonitor-1.0.7-windows-x64.zip" to "windows update",
            "Daemonitor-1.0.7-macos-arm64.dmg" to "macos package",
            "Daemonitor-1.0.7-macos-arm64.zip" to "macos update",
        )
        assets.forEach { (name, content) -> assetsDir.resolve(name).writeText(content) }

        val result = ProcessBuilder(
            bashExecutable(),
            "scripts/generate-release-metadata.sh",
            assetsDir.toString(),
            outputDir.toString(),
            "1.0.7",
            "v1.0.7",
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
            assertEquals(expected + "\n", assetsDir.resolve("$name.sha256").readText())
        }

        val latest = outputDir.resolve("latest.json").readText()
        assertTrue(latest.contains("\"schemaVersion\": 2"), latest)
        assertTrue(latest.contains("\"version\": \"1.0.7\""), latest)
        assertTrue(latest.contains("\"tag\": \"v1.0.7\""), latest)
        assertTrue(latest.contains("\"platform\": \"linux\""), latest)
        assertTrue(latest.contains("\"platform\": \"windows\""), latest)
        assertTrue(latest.contains("\"platform\": \"macos\""), latest)
        assertTrue(latest.contains("\"arch\": \"arm64\""), latest)
        assertTrue(latest.contains("\"arch\": \"x64\""), latest)
        assertTrue(latest.contains("\"role\": \"update\""), latest)
        assertTrue(latest.contains("\"role\": \"installer\""), latest)
        assertTrue(
            latest.contains(
                "\"url\": \"https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-arm64.zip\"",
            ),
            latest,
        )
        assertTrue(latest.contains("\"sha256\": \"${sha256(assetsDir.resolve("Daemonitor-1.0.7-linux-x64.deb"))}\""), latest)

        assertEquals(latest, outputDir.resolve("update.json").readText())
    }

    @Test
    fun `release workflow publishes metadata after all native assets exist`() {
        val workflow = Path.of(".github/workflows/release.yml").readText()

        assertTrue(workflow.contains("Read package version"), workflow)
        assertTrue(workflow.contains("Detect CPU architecture"), workflow)
        assertTrue(workflow.contains("upload-artifact"), workflow)
        assertTrue(workflow.contains("download-artifact"), workflow)
        assertTrue(workflow.contains("scripts/generate-release-metadata.sh"), workflow)
        assertTrue(workflow.contains("release-metadata/latest.json"), workflow)
        assertTrue(workflow.contains("release-metadata/update.json"), workflow)
        assertTrue(workflow.contains("release-metadata/checksums.txt"), workflow)
        assertTrue(workflow.contains("update_ext: zip"), workflow)
        assertTrue(workflow.contains("update_ext: tar.gz"), workflow)
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
