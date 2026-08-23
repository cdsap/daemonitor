package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.update.DesktopUpdateApplier
import io.github.cdsap.daemonitor.update.DesktopUpdateInstaller
import io.github.cdsap.daemonitor.update.GitHubReleaseUpdateSource
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstaller

/** Application service for checking, downloading, and installing desktop updates. */
class UpdateService(
    private val checker: suspend () -> UpdateCheckResult = {
        GitHubReleaseUpdateSource().check(BuildInfo.current.version)
    },
    private val installer: UpdateInstaller = DesktopUpdateInstaller(),
    private val applier: UpdateApplier = DesktopUpdateApplier(),
) {
    suspend fun check(): UpdateCheckResult = checker()

    suspend fun download(
        candidate: UpdateCandidate,
        onProgress: (Double?) -> Unit = {},
    ): StagedUpdate? = installer.prepare(candidate, onProgress)

    fun install(staged: StagedUpdate) {
        applier.applyAfterExit(staged)
    }
}
