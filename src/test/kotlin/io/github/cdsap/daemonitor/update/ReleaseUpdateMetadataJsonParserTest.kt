package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReleaseUpdateMetadataJsonParserTest {

    @Test
    fun `parses update metadata assets with checksums`() {
        val metadata = ReleaseUpdateMetadataJsonParser.parse(
            """
            {
              "schemaVersion": 2,
              "name": "Daemonitor",
              "version": "1.0.4",
              "tag": "v1.0.4",
              "assets": [
                {
                  "platform": "macos",
                  "arch": "arm64",
                  "role": "update",
                  "fileName": "Daemonitor-1.0.4-macos-arm64.zip",
                  "url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.4/Daemonitor-1.0.4-macos-arm64.zip",
                  "sha256": "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
                  "size": 128575584
                }
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(metadata)
        assertEquals(2, metadata.schemaVersion)
        assertEquals("1.0.4", metadata.version)
        assertEquals("v1.0.4", metadata.tag)
        assertEquals("Daemonitor-1.0.4-macos-arm64.zip", metadata.assets.single().fileName)
        assertEquals("arm64", metadata.assets.single().arch)
        assertEquals("update", metadata.assets.single().role)
        assertEquals("24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1", metadata.assets.single().sha256)
        assertEquals(128575584, metadata.assets.single().sizeBytes)
    }

    @Test
    fun `rejects unsupported metadata schema`() {
        assertNull(ReleaseUpdateMetadataJsonParser.parse("""{"schemaVersion": 99, "version": "1.0.4", "tag": "v1.0.4", "assets": []}"""))
    }

    @Test
    fun `selects metadata update package for automatic installs`() {
        val metadata = ReleaseUpdateMetadata(
            schemaVersion = 2,
            version = "1.0.4",
            tag = "v1.0.4",
            assets = listOf(
                ReleaseUpdateAsset(
                    platform = "macos",
                    fileName = "Daemonitor-1.0.4-macos-arm64.dmg",
                    url = "https://example.com/Daemonitor-1.0.4-macos-arm64.dmg",
                    sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
                    sizeBytes = 128575584,
                    arch = "arm64",
                    role = "installer",
                ),
                ReleaseUpdateAsset(
                    platform = "macos",
                    fileName = "Daemonitor-1.0.4-macos-arm64.zip",
                    url = "https://example.com/Daemonitor-1.0.4-macos-arm64.zip",
                    sha256 = "34fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c2",
                    sizeBytes = 120000000,
                    arch = "arm64",
                    role = "update",
                ),
            ),
        )

        val result = ReleaseMetadataUpdateSelector.select(
            metadata = metadata,
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.4",
            currentVersion = "1.0.3",
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            installation = InstallationInfo(
                platform = DesktopPlatform.MACOS,
                architecture = CpuArchitecture.ARM64,
                kind = InstallationKind.MACOS_APP_BUNDLE,
                installRoot = java.nio.file.Path.of("/Applications/Daemonitor.app"),
                relaunchCommand = listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"),
            ),
        )

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("1.0.4", available.candidate.version)
        assertEquals("Daemonitor-1.0.4-macos-arm64.zip", available.candidate.assetName)
        assertEquals(UpdateInstallMode.Automatic, available.candidate.installMode)
        assertEquals("34fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c2", available.candidate.sha256)
        assertEquals(120000000, available.candidate.sizeBytes)
    }
}
