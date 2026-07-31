package io.github.cdsap.daemonitor.update

data class UpdateCandidate(
    val version: String,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val candidate: UpdateCandidate) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UnsupportedPlatform(val platform: DesktopPlatform) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}
