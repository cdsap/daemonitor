package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateArtifactMatcherTest {

    @Test
    fun `prefers arch specific update package when automatic updates are supported`() {
        val selected = UpdateArtifactMatcher.selectAsset(
            assets = listOf(
                NamedReleaseAsset("Daemonitor-1.0.7-macos.dmg", "https://example.com/dmg"),
                NamedReleaseAsset("Daemonitor-1.0.7-macos-arm64.dmg", "https://example.com/dmg-arm"),
                NamedReleaseAsset("Daemonitor-1.0.7-macos-x64.zip", "https://example.com/zip-x64"),
                NamedReleaseAsset("Daemonitor-1.0.7-macos-arm64.zip", "https://example.com/zip-arm"),
            ),
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            preferAutomatic = true,
        )

        assertEquals("Daemonitor-1.0.7-macos-arm64.zip", selected?.fileName)
    }

    @Test
    fun `prefers installer when automatic updates are unavailable`() {
        val selected = UpdateArtifactMatcher.selectAsset(
            assets = listOf(
                NamedReleaseAsset("Daemonitor-1.0.7-linux-x64.tar.gz", "https://example.com/tar"),
                NamedReleaseAsset("Daemonitor-1.0.7-linux-x64.deb", "https://example.com/deb"),
            ),
            platform = DesktopPlatform.LINUX,
            architecture = CpuArchitecture.X64,
            preferAutomatic = false,
        )

        assertEquals("Daemonitor-1.0.7-linux-x64.deb", selected?.fileName)
    }

    @Test
    fun `rejects architecture mismatches`() {
        assertNull(
            UpdateArtifactMatcher.selectAsset(
                assets = listOf(
                    NamedReleaseAsset("Daemonitor-1.0.7-windows-arm64.zip", "https://example.com/zip"),
                ),
                platform = DesktopPlatform.WINDOWS,
                architecture = CpuArchitecture.X64,
                preferAutomatic = true,
            ),
        )
    }

    @Test
    fun `falls back to legacy platform only assets`() {
        val selected = UpdateArtifactMatcher.selectAsset(
            assets = listOf(
                NamedReleaseAsset("Daemonitor-1.0.6-macos.dmg", "https://example.com/dmg"),
            ),
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            preferAutomatic = false,
        )

        assertEquals("Daemonitor-1.0.6-macos.dmg", selected?.fileName)
    }
}

class ReleaseUpdateSelectorTest {

    @Test
    fun `selects matching platform asset when release is newer`() {
        val result = ReleaseUpdateSelector.select(
            release = release("v1.0.3"),
            currentVersion = "1.0.2",
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            installation = automaticMac(),
        )

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("1.0.3", available.candidate.version)
        assertEquals("Daemonitor-1.0.3-macos-arm64.zip", available.candidate.assetName)
        assertEquals(UpdateInstallMode.Automatic, available.candidate.installMode)
        assertEquals(UpdateArtifactRole.UpdatePackage, available.candidate.role)
    }

    @Test
    fun `reports up to date when latest release is not newer`() {
        val result = ReleaseUpdateSelector.select(
            release = release("v1.0.2"),
            currentVersion = "1.0.2",
            platform = DesktopPlatform.WINDOWS,
            architecture = CpuArchitecture.X64,
            installation = manualWindows(),
        )

        assertEquals(UpdateCheckResult.UpToDate("1.0.2"), result)
    }

    @Test
    fun `fails when release does not include a matching installer`() {
        val result = ReleaseUpdateSelector.select(
            release = release("v1.0.3"),
            currentVersion = "1.0.2",
            platform = DesktopPlatform.LINUX,
            architecture = CpuArchitecture.X64,
            installation = packageManagedLinux(),
        )

        assertEquals(
            UpdateCheckResult.Failed("No linux installer was attached to v1.0.3"),
            result,
        )
    }

    @Test
    fun `uses manual installer when automatic updates are unsupported`() {
        val result = ReleaseUpdateSelector.select(
            release = GitHubRelease(
                version = "v1.0.7",
                releaseUrl = "https://example.com/release",
                assets = listOf(
                    GitHubReleaseAsset(
                        "Daemonitor-1.0.7-linux-x64.tar.gz",
                        "https://example.com/Daemonitor-1.0.7-linux-x64.tar.gz",
                    ),
                    GitHubReleaseAsset(
                        "Daemonitor-1.0.7-linux-x64.deb",
                        "https://example.com/Daemonitor-1.0.7-linux-x64.deb",
                    ),
                ),
            ),
            currentVersion = "1.0.6",
            platform = DesktopPlatform.LINUX,
            architecture = CpuArchitecture.X64,
            installation = packageManagedLinux(),
        )

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("Daemonitor-1.0.7-linux-x64.deb", available.candidate.assetName)
        assertEquals(UpdateInstallMode.Manual, available.candidate.installMode)
    }

    private fun release(version: String): GitHubRelease = GitHubRelease(
        version = version,
        releaseUrl = "https://example.com/release",
        assets = listOf(
            GitHubReleaseAsset(
                "Daemonitor-${version.removePrefix("v")}-macos-arm64.zip",
                "https://example.com/Daemonitor-${version.removePrefix("v")}-macos-arm64.zip",
            ),
            GitHubReleaseAsset(
                "Daemonitor-${version.removePrefix("v")}-macos-arm64.dmg",
                "https://example.com/Daemonitor-${version.removePrefix("v")}-macos-arm64.dmg",
            ),
            GitHubReleaseAsset(
                "Daemonitor-${version.removePrefix("v")}-windows-x64.msi",
                "https://example.com/Daemonitor-${version.removePrefix("v")}-windows-x64.msi",
            ),
        ),
    )

    private fun automaticMac(): InstallationInfo = InstallationInfo(
        platform = DesktopPlatform.MACOS,
        architecture = CpuArchitecture.ARM64,
        kind = InstallationKind.MACOS_APP_BUNDLE,
        installRoot = java.nio.file.Path.of("/Applications/Daemonitor.app"),
        relaunchCommand = listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"),
    )

    private fun manualWindows(): InstallationInfo = InstallationInfo(
        platform = DesktopPlatform.WINDOWS,
        architecture = CpuArchitecture.X64,
        kind = InstallationKind.DEVELOPMENT,
        installRoot = null,
        relaunchCommand = emptyList(),
    )

    private fun packageManagedLinux(): InstallationInfo = InstallationInfo(
        platform = DesktopPlatform.LINUX,
        architecture = CpuArchitecture.X64,
        kind = InstallationKind.LINUX_PACKAGE_MANAGED,
        installRoot = java.nio.file.Path.of("/usr/lib/daemonitor"),
        relaunchCommand = listOf("/usr/lib/daemonitor/bin/Daemonitor"),
    )
}
