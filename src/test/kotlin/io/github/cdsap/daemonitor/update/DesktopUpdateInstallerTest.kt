package io.github.cdsap.daemonitor.update

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopUpdateInstallerTest {

    @Test
    fun `downloads update and opens local installer path`(@TempDir tmp: Path) = runTest {
        val opened = mutableListOf<Path>()
        val progress = mutableListOf<Double?>()
        val candidate = UpdateCandidate(
            version = "1.0.4",
            releaseUrl = "https://example.com/release",
            assetName = "Daemonitor-1.0.4-macos.dmg",
            downloadUrl = "https://example.com/Daemonitor-1.0.4-macos.dmg",
            sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
            sizeBytes = 128575584,
        )
        val installer = DesktopUpdateInstaller(
            updateDirectory = tmp,
            opener = { opened.add(it) },
            downloader = { update, directory, onProgress ->
                onProgress(0.5)
                directory.resolve(update.assetName).also { path ->
                    Files.writeString(path, "installer")
                }
            },
        )

        installer.open(candidate) { progress.add(it) }

        assertEquals(listOf<Double?>(0.5), progress)
        assertEquals(listOf(tmp.resolve("Daemonitor-1.0.4-macos.dmg")), opened)
        assertTrue(Files.exists(opened.single()))
    }

    @Test
    fun `matches expected installer checksum`() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("installer".toByteArray())

        assertTrue(
            UpdateDownloadVerifier.matches(
                digest,
                "9c0d294c05fc1d88d698034609bb81c0c69196327594e4c69d2915c80fd9850c",
            ),
        )
    }
}
