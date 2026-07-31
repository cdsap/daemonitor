package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubReleaseJsonParserTest {

    @Test
    fun `parses release tag url and assets`() {
        val release = GitHubReleaseJsonParser.parse(
            """
            {
              "tag_name": "v1.0.3",
              "name": "v1.0.3",
              "html_url": "https://github.com/cdsap/daemonitor/releases/tag/v1.0.3",
              "assets": [
                {
                  "name": "Daemonitor-1.0.3-linux.deb",
                  "browser_download_url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.3/Daemonitor-1.0.3-linux.deb"
                },
                {
                  "name": "Daemonitor-1.0.3-macos.dmg",
                  "browser_download_url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.3/Daemonitor-1.0.3-macos.dmg"
                }
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(release)
        assertEquals("v1.0.3", release.version)
        assertEquals("https://github.com/cdsap/daemonitor/releases/tag/v1.0.3", release.releaseUrl)
        assertEquals(
            listOf("Daemonitor-1.0.3-linux.deb", "Daemonitor-1.0.3-macos.dmg"),
            release.assets.map { it.name },
        )
    }

    @Test
    fun `returns null when required release metadata is absent`() {
        assertNull(GitHubReleaseJsonParser.parse("""{"assets": []}"""))
    }
}
