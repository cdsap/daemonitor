package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstaller
import io.github.cdsap.daemonitor.update.CpuArchitecture
import io.github.cdsap.daemonitor.update.DesktopPlatform
import io.github.cdsap.daemonitor.update.InstallationInfo
import io.github.cdsap.daemonitor.update.InstallationKind
import io.github.cdsap.daemonitor.update.UpdateArtifactRole
import io.github.cdsap.daemonitor.update.UpdateInstallMode
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class UpdateServiceTest {
    @Test
    fun `check download and install delegate to collaborators`() = runTest {
        val candidate = UpdateCandidate(
            version = "1.2.3",
            releaseUrl = "https://example.test/release",
            assetName = "daemonitor.zip",
            downloadUrl = "https://example.test/daemonitor.zip",
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            role = UpdateArtifactRole.UpdatePackage,
            installMode = UpdateInstallMode.Automatic,
        )
        val stagedRoot = createTempDirectory("update-service-test")
        val staged = StagedUpdate(
            candidate = candidate,
            artifactPath = stagedRoot.resolve("artifact.zip"),
            payloadPath = stagedRoot.resolve("payload"),
            installation = InstallationInfo(
                platform = DesktopPlatform.MACOS,
                architecture = CpuArchitecture.ARM64,
                kind = InstallationKind.MACOS_APP_BUNDLE,
                installRoot = stagedRoot,
                relaunchCommand = listOf("/usr/bin/open", "-n", stagedRoot.toString()),
            ),
        )
        var checked = 0
        var downloaded: UpdateCandidate? = null
        var installed: StagedUpdate? = null

        val service = UpdateService(
            checker = {
                checked += 1
                UpdateCheckResult.Available(candidate)
            },
            installer = UpdateInstaller { c, _ ->
                downloaded = c
                staged
            },
            applier = UpdateApplier { installed = it },
        )

        assertEquals(UpdateCheckResult.Available(candidate), service.check())
        assertEquals(1, checked)
        assertSame(staged, service.download(candidate))
        assertEquals(candidate, downloaded)
        service.install(staged)
        assertSame(staged, installed)
    }
}
