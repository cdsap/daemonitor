package io.github.cdsap.daemonitor.application.update

import io.github.cdsap.daemonitor.update.UpdateCheckResult

/** Application port for discovering whether a newer release is available. */
fun interface UpdateSource {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
