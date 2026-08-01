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
              "schemaVersion": 1,
              "name": "Daemonitor",
              "version": "1.0.4",
              "tag": "v1.0.4",
              "assets": [
                {
                  "platform": "macos",
                  "fileName": "Daemonitor-1.0.4-macos.dmg",
                  "url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.4/Daemonitor-1.0.4-macos.dmg",
                  "sha256": "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
                  "size": 128575584
                }
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(metadata)
        assertEquals("1.0.4", metadata.version)
        assertEquals("v1.0.4", metadata.tag)
        assertEquals("Daemonitor-1.0.4-macos.dmg", metadata.assets.single().fileName)
        assertEquals("24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1", metadata.assets.single().sha256)
        assertEquals(128575584, metadata.assets.single().sizeBytes)
    }

    @Test
    fun `rejects unsupported metadata schema`() {
        assertNull(ReleaseUpdateMetadataJsonParser.parse("""{"schemaVersion": 99, "version": "1.0.4", "tag": "v1.0.4", "assets": []}"""))
    }

    @Test
    fun `selects metadata asset for current platform`() {
        val metadata = ReleaseUpdateMetadata(
            version = "1.0.4",
            tag = "v1.0.4",
            assets = listOf(
                ReleaseUpdateAsset(
                    platform = "macos",
                    fileName = "Daemonitor-1.0.4-macos.dmg",
                    url = "https://example.com/Daemonitor-1.0.4-macos.dmg",
                    sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
                    sizeBytes = 128575584,
                ),
            ),
        )

        val result = ReleaseMetadataUpdateSelector.select(
            metadata = metadata,
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.4",
            currentVersion = "1.0.3",
            platform = DesktopPlatform.MACOS,
        )

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("1.0.4", available.candidate.version)
        assertEquals("Daemonitor-1.0.4-macos.dmg", available.candidate.assetName)
        assertEquals("24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1", available.candidate.sha256)
        assertEquals(128575584, available.candidate.sizeBytes)
    }
}
