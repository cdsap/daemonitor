package io.github.cdsap.daemonitor.application.update

import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import io.github.cdsap.daemonitor.application.platform.UrlOpener
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateCandidate
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstaller

/**
 * Presentation-facing update facade. Coordinates check / prepare / apply and platform side effects
 * without exposing infrastructure types to the UI layer.
 */
class UpdateService(
    private val checkForUpdate: CheckForUpdate,
    private val prepareUpdate: PrepareUpdate,
    private val applyUpdate: ApplyUpdate,
    private val urlOpener: UrlOpener,
) {
    suspend fun check(): UpdateCheckResult = checkForUpdate()

    suspend fun prepare(
        candidate: UpdateCandidate,
        onProgress: (Double?) -> Unit,
    ): StagedUpdate? = prepareUpdate(candidate, onProgress)

    fun applyAndRestart(staged: StagedUpdate) = applyUpdate(staged)

    fun openReleaseUrl(url: String) = urlOpener.open(url)

    companion object {
        /**
         * No-op service for settings-only construction/tests. Does not touch network, filesystem,
         * or process lifecycle.
         */
        fun inactive(): UpdateService = UpdateService(
            checkForUpdate = CheckForUpdate(
                source = UpdateSource { UpdateCheckResult.UpToDate(it) },
                currentVersion = { "" },
            ),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(
                applier = UpdateApplier {},
                processExiter = ProcessExiter {},
            ),
            urlOpener = UrlOpener {},
        )
    }
}
