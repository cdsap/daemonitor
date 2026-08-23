package io.github.cdsap.daemonitor.application.update

import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateInstaller

/** Downloads and stages an update package, or hands a manual installer to the OS. */
class PrepareUpdate(
    private val installer: UpdateInstaller,
) {
    suspend operator fun invoke(
        candidate: UpdateCandidate,
        onProgress: (Double?) -> Unit,
    ): StagedUpdate? = installer.prepare(candidate, onProgress)
}
