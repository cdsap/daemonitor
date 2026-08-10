package io.github.cdsap.daemonitor.update

/**
 * Selects the best release artifact for the current OS/arch and installation capabilities.
 *
 * Preference order when automatic updates are supported:
 * 1. platform+arch update package (zip/tar.gz)
 * 2. platform-only update package
 * 3. platform+arch installer (manual fallback)
 * 4. platform-only installer
 *
 * Preference order when automatic updates are not supported:
 * 1. platform+arch installer
 * 2. platform-only installer
 * 3. matching update package offered as a manual download
 */
internal object UpdateArtifactMatcher {
    fun selectAsset(
        assets: List<NamedReleaseAsset>,
        platform: DesktopPlatform,
        architecture: CpuArchitecture,
        preferAutomatic: Boolean,
    ): NamedReleaseAsset? {
        if (platform == DesktopPlatform.UNKNOWN) return null
        val matching = assets.filter { asset ->
            matchesPlatform(asset.fileName, platform) && matchesArchitecture(asset.fileName, architecture)
        }
        if (matching.isEmpty()) return null

        val updatePackages = matching.filter { roleOf(it.fileName) == UpdateArtifactRole.UpdatePackage }
        val installers = matching.filter { roleOf(it.fileName) == UpdateArtifactRole.Installer }

        return if (preferAutomatic) {
            preferred(updatePackages, architecture)
                ?: preferred(installers, architecture)
                ?: matching.firstOrNull()
        } else {
            preferred(installers, architecture)
                ?: preferred(updatePackages, architecture)
                ?: matching.firstOrNull()
        }
    }

    fun roleOf(fileName: String): UpdateArtifactRole {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                UpdateArtifactRole.UpdatePackage
            else -> UpdateArtifactRole.Installer
        }
    }

    fun matchesPlatform(fileName: String, platform: DesktopPlatform): Boolean {
        val lower = fileName.lowercase()
        val token = "-${platform.metadataName}"
        if (!lower.contains(token)) return false
        // Reject checksum sidecars and metadata.
        if (lower.endsWith(".sha256") || lower.endsWith(".json") || lower.endsWith(".txt")) return false
        return true
    }

    fun matchesArchitecture(fileName: String, architecture: CpuArchitecture): Boolean {
        if (architecture == CpuArchitecture.UNKNOWN) return true
        val lower = fileName.lowercase()
        val hasArchToken = ARCH_TOKENS.any { token -> "-$token" in lower || ".$token." in lower }
        if (!hasArchToken) return true // legacy assets without arch are acceptable fallbacks
        return when (architecture) {
            CpuArchitecture.ARM64 ->
                lower.contains("-arm64") || lower.contains("-aarch64") || lower.contains(".arm64.")
            CpuArchitecture.X64 ->
                lower.contains("-x64") || lower.contains("-amd64") || lower.contains("-x86_64") ||
                    lower.contains(".x64.")
            CpuArchitecture.UNKNOWN -> true
        }
    }

    fun architectureOf(fileName: String): CpuArchitecture {
        val lower = fileName.lowercase()
        return when {
            lower.contains("-arm64") || lower.contains("-aarch64") || lower.contains(".arm64.") ->
                CpuArchitecture.ARM64
            lower.contains("-x64") || lower.contains("-amd64") || lower.contains("-x86_64") ||
                lower.contains(".x64.") -> CpuArchitecture.X64
            else -> CpuArchitecture.UNKNOWN
        }
    }

    private fun preferred(
        assets: List<NamedReleaseAsset>,
        architecture: CpuArchitecture,
    ): NamedReleaseAsset? {
        if (assets.isEmpty()) return null
        val archSpecific = assets.filter { architectureOf(it.fileName) == architecture }
        if (architecture != CpuArchitecture.UNKNOWN && archSpecific.isNotEmpty()) {
            return archSpecific.first()
        }
        val legacy = assets.filter { architectureOf(it.fileName) == CpuArchitecture.UNKNOWN }
        return legacy.firstOrNull() ?: assets.first()
    }

    private val ARCH_TOKENS = listOf("arm64", "aarch64", "x64", "amd64", "x86_64")
}

internal data class NamedReleaseAsset(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val platform: String? = null,
    val architecture: String? = null,
    val role: String? = null,
)
