package io.github.cdsap.daemonitor.application.update

import io.github.cdsap.daemonitor.BuildInfo
import io.github.cdsap.daemonitor.update.UpdateCheckResult

/** Checks whether a newer application release is available for the current install. */
class CheckForUpdate(
    private val source: UpdateSource,
    private val currentVersion: () -> String = { BuildInfo.current.version },
) {
    suspend operator fun invoke(): UpdateCheckResult = source.check(currentVersion())
}
