package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReleaseUpdateSelectorTest {

    @Test
    fun `selects matching platform asset when release is newer`() {
        val result = ReleaseUpdateSelector.select(
            release = release("v1.0.3"),
            currentVersion = "1.0.2",
            platform = DesktopPlatform.MACOS,
        )

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("1.0.3", available.candidate.version)
        assertEquals("Daemonitor-1.0.3-macos.dmg", available.candidate.assetName)
        assertEquals("https://example.com/Daemonitor-1.0.3-macos.dmg", available.candidate.downloadUrl)
    }

    @Test
    fun `reports up to date when latest release is not newer`() {
        val result = ReleaseUpdateSelector.select(
            release = release("v1.0.2"),
            currentVersion = "1.0.2",
            platform = DesktopPlatform.WINDOWS,
        )

        assertEquals(UpdateCheckResult.UpToDate("1.0.2"), result)
    }

    @Test
    fun `fails when release does not include a matching installer`() {
        val result = ReleaseUpdateSelector.select(
            release = release("v1.0.3"),
            currentVersion = "1.0.2",
            platform = DesktopPlatform.LINUX,
        )

        assertEquals(
            UpdateCheckResult.Failed("No linux installer was attached to v1.0.3"),
            result,
        )
    }

    private fun release(version: String): GitHubRelease = GitHubRelease(
        version = version,
        releaseUrl = "https://example.com/release",
        assets = listOf(
            GitHubReleaseAsset("Daemonitor-${version.removePrefix("v")}-macos.dmg", "https://example.com/Daemonitor-${version.removePrefix("v")}-macos.dmg"),
            GitHubReleaseAsset("Daemonitor-${version.removePrefix("v")}-windows.msi", "https://example.com/Daemonitor-${version.removePrefix("v")}-windows.msi"),
        ),
    )
}
