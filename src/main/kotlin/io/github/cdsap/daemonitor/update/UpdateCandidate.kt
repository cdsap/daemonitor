package io.github.cdsap.daemonitor.update

enum class UpdateInstallMode {
    /** Download, validate, stage, then apply on Restart and Update. */
    Automatic,

    /** Download/verify when possible, then hand off to a manual installer or release page. */
    Manual,
}

enum class UpdateArtifactRole {
    UpdatePackage,
    Installer,
}

data class UpdateCandidate(
    val version: String,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val platform: DesktopPlatform = DesktopPlatform.UNKNOWN,
    val architecture: CpuArchitecture = CpuArchitecture.UNKNOWN,
    val role: UpdateArtifactRole = UpdateArtifactRole.Installer,
    val installMode: UpdateInstallMode = UpdateInstallMode.Manual,
)

sealed interface UpdateCheckResult {
    data class Available(val candidate: UpdateCandidate) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UnsupportedPlatform(val platform: DesktopPlatform) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}
