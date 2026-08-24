package io.github.cdsap.daemonitor.application.update

import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import io.github.cdsap.daemonitor.update.StagedUpdate
import io.github.cdsap.daemonitor.update.UpdateApplier

/**
 * Schedules a staged update to apply after this process exits, then exits so replacement can proceed.
 */
class ApplyUpdate(
    private val applier: UpdateApplier,
    private val processExiter: ProcessExiter,
) {
    operator fun invoke(staged: StagedUpdate) {
        applier.applyAfterExit(staged)
        processExiter.exit()
    }
}
