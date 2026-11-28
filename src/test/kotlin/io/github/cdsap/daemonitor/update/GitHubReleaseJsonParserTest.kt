package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubReleaseJsonParserTest {
    @Test
    fun `uses direct release and asset fields regardless of ordering`() {
        val release = requireNotNull(GitHubReleaseJsonParser.parse("""{
            "author":{"html_url":"wrong","tag_name":"wrong"},
            "tag_name":"v\u0031.0","html_url":"https:\/\/example.com\/release",
            "assets":[
                {"browser_download_url":"https://example.com/a","name":"a\"}\\\n\u263a"},
                {"name":"incomplete"},
                {"browser_download_url":"wrong","nested":{"name":"nested"}}
            ],"unrelated":{"name":"outside","browser_download_url":"wrong"}
        }"""))
        assertEquals("v1.0", release.version)
        assertEquals("https://example.com/release", release.releaseUrl)
        assertEquals(listOf(GitHubReleaseAsset("a\"}\\\n☺", "https://example.com/a")), release.assets)
        assertNull(GitHubReleaseJsonParser.parse("""{"tag_name":"v1","html_url":"url",}"""))
    }

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
