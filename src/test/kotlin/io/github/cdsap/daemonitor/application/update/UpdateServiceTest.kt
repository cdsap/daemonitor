package io.github.cdsap.daemonitor.application.update

import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import io.github.cdsap.daemonitor.application.platform.UrlOpener
import io.github.cdsap.daemonitor.update.CpuArchitecture
import io.github.cdsap.daemonitor.update.DesktopPlatform
import io.github.cdsap.daemonitor.update.InstallationInfo
import io.github.cdsap.daemonitor.update.InstallationKind
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateArtifactRole
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstallMode
import io.github.cdsap.daemonitor.update.UpdateInstaller
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class UpdateServiceTest {

    @Test
    fun `check delegates to the update source with the current version`() = runTest {
        val seenVersions = mutableListOf<String>()
        val candidate = candidate()
        val service = UpdateService(
            checkForUpdate = CheckForUpdate(
                source = UpdateSource { version ->
                    seenVersions += version
                    UpdateCheckResult.Available(candidate)
                },
                currentVersion = { "1.0.2" },
            ),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
            urlOpener = UrlOpener {},
        )

        assertEquals(UpdateCheckResult.Available(candidate), service.check())
        assertEquals(listOf("1.0.2"), seenVersions)
    }

    @Test
    fun `prepare reports progress and returns the staged update`() = runTest {
        val progress = mutableListOf<Double?>()
        val candidate = candidate(installMode = UpdateInstallMode.Automatic)
        val staged = staged(candidate)
        val service = UpdateService(
            checkForUpdate = CheckForUpdate(UpdateSource { UpdateCheckResult.UpToDate("1.0.2") }),
            prepareUpdate = PrepareUpdate(
                UpdateInstaller { update, onProgress ->
                    assertEquals(candidate, update)
                    onProgress(0.25)
                    onProgress(1.0)
                    staged
                },
            ),
            applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
            urlOpener = UrlOpener {},
        )

        val result: StagedUpdate? = service.prepare(candidate) { progress += it }

        assertEquals(staged, result)
        assertEquals(listOf<Double?>(0.25, 1.0), progress)
    }

    @Test
    fun `prepare can return null for manual installer handoff`() = runTest {
        val service = UpdateService(
            checkForUpdate = CheckForUpdate(UpdateSource { UpdateCheckResult.UpToDate("1.0.2") }),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
            urlOpener = UrlOpener {},
        )

        assertNull(service.prepare(candidate()) {})
    }

    @Test
    fun `apply and restart applies then exits in order`() {
        val events = mutableListOf<String>()
        val staged = staged(candidate(installMode = UpdateInstallMode.Automatic))
        val service = UpdateService(
            checkForUpdate = CheckForUpdate(UpdateSource { UpdateCheckResult.UpToDate("1.0.2") }),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(
                applier = UpdateApplier {
                    events += "apply"
                    assertEquals(staged, it)
                },
                processExiter = ProcessExiter { events += "exit" },
            ),
            urlOpener = UrlOpener {},
        )

        service.applyAndRestart(staged)

        assertEquals(listOf("apply", "exit"), events)
    }

    @Test
    fun `apply and restart does not exit when apply fails`() {
        var exited = 0
        val staged = staged(candidate(installMode = UpdateInstallMode.Automatic))
        val service = UpdateService(
            checkForUpdate = CheckForUpdate(UpdateSource { UpdateCheckResult.UpToDate("1.0.2") }),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(
                applier = UpdateApplier { error("apply failed") },
                processExiter = ProcessExiter { exited += 1 },
            ),
            urlOpener = UrlOpener {},
        )

        assertFailsWith<IllegalStateException> { service.applyAndRestart(staged) }
        assertEquals(0, exited)
    }

    @Test
    fun `open release url uses the injected opener`() {
        val opened = mutableListOf<String>()
        val service = UpdateService(
            checkForUpdate = CheckForUpdate(UpdateSource { UpdateCheckResult.UpToDate("1.0.2") }),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
            urlOpener = UrlOpener { opened += it },
        )

        service.openReleaseUrl("https://example.com/release")

        assertEquals(listOf("https://example.com/release"), opened)
    }

    private fun candidate(
        installMode: UpdateInstallMode = UpdateInstallMode.Manual,
    ): UpdateCandidate = UpdateCandidate(
        version = "1.0.3",
        releaseUrl = "https://example.com/release",
        assetName = "Daemonitor-1.0.3-macos-arm64.zip",
        downloadUrl = "https://example.com/Daemonitor-1.0.3-macos-arm64.zip",
        platform = DesktopPlatform.MACOS,
        architecture = CpuArchitecture.ARM64,
        role = UpdateArtifactRole.UpdatePackage,
        installMode = installMode,
    )

    private fun staged(candidate: UpdateCandidate): StagedUpdate = StagedUpdate(
        candidate = candidate,
        artifactPath = Path.of("/tmp/artifact.zip"),
        payloadPath = Path.of("/tmp/Daemonitor.app"),
        installation = InstallationInfo(
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            kind = InstallationKind.MACOS_APP_BUNDLE,
            installRoot = Path.of("/Applications/Daemonitor.app"),
            relaunchCommand = listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"),
        ),
    )
}

class CheckForUpdateTest {

    @Test
    fun `propagates source failures as results when the source catches them`() = runTest {
        val check = CheckForUpdate(
            source = UpdateSource { UpdateCheckResult.Failed("offline") },
            currentVersion = { "1.0.0" },
        )
        assertEquals(UpdateCheckResult.Failed("offline"), check())
    }
}
