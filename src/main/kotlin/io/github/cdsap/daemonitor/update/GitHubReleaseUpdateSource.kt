package io.github.cdsap.daemonitor.update

import io.github.cdsap.daemonitor.application.update.UpdateSource
import io.github.cdsap.daemonitor.json.Json
import io.github.cdsap.daemonitor.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GitHubReleaseUpdateSource(
    private val repository: String = "cdsap/daemonitor",
    private val platform: DesktopPlatform = DesktopPlatform.current(),
    private val architecture: CpuArchitecture = CpuArchitecture.current(),
    private val installation: InstallationInfo = InstallationLocator.current(
        platform = platform,
        architecture = architecture,
    ),
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) : UpdateSource {
    override suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (platform == DesktopPlatform.UNKNOWN || architecture == CpuArchitecture.UNKNOWN) {
            return@withContext UpdateCheckResult.UnsupportedPlatform(platform)
        }

        try {
            val request = HttpRequest.newBuilder()
                .uri(URI("https://api.github.com/repos/$repository/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Daemonitor")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext UpdateCheckResult.Failed("GitHub returned HTTP ${response.statusCode()}")
            }
            val release = GitHubReleaseJsonParser.parse(response.body())
                ?: return@withContext UpdateCheckResult.Failed("Could not read the latest release metadata")
            val metadata = release.updateMetadataAsset()?.let { asset ->
                fetchUpdateMetadata(asset.downloadUrl)
            }
            if (metadata != null) {
                ReleaseMetadataUpdateSelector.select(
                    metadata = metadata,
                    releaseUrl = release.releaseUrl,
                    currentVersion = currentVersion,
                    platform = platform,
                    architecture = architecture,
                    installation = installation,
                )
            } else {
                ReleaseUpdateSelector.select(
                    release = release,
                    currentVersion = currentVersion,
                    platform = platform,
                    architecture = architecture,
                    installation = installation,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            UpdateCheckResult.Failed(error.message ?: error::class.simpleName ?: "Update check failed")
        }
    }

    private fun GitHubRelease.updateMetadataAsset(): GitHubReleaseAsset? =
        assets.firstOrNull { it.name == "update.json" }
            ?: assets.firstOrNull { it.name == "latest.json" }

    private fun fetchUpdateMetadata(url: String): ReleaseUpdateMetadata? {
        val request = HttpRequest.newBuilder()
            .uri(URI(url))
            .header("Accept", "application/json")
            .header("User-Agent", "Daemonitor")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        return ReleaseUpdateMetadataJsonParser.parse(response.body())
    }
}

internal data class GitHubRelease(
    val version: String,
    val releaseUrl: String,
    val assets: List<GitHubReleaseAsset>,
)

internal data class GitHubReleaseAsset(val name: String, val downloadUrl: String)

internal data class ReleaseUpdateMetadata(
    val schemaVersion: Int,
    val version: String,
    val tag: String,
    val assets: List<ReleaseUpdateAsset>,
)

internal data class ReleaseUpdateAsset(
    val platform: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val arch: String? = null,
    val role: String? = null,
)

internal object ReleaseMetadataUpdateSelector {
    fun select(
        metadata: ReleaseUpdateMetadata,
        releaseUrl: String,
        currentVersion: String,
        platform: DesktopPlatform,
        architecture: CpuArchitecture = CpuArchitecture.UNKNOWN,
        installation: InstallationInfo = InstallationInfo(
            platform = platform,
            architecture = architecture,
            kind = InstallationKind.UNSUPPORTED,
            installRoot = null,
            relaunchCommand = emptyList(),
        ),
    ): UpdateCheckResult {
        if (!VersionComparator.isNewer(metadata.version, currentVersion)) {
            return UpdateCheckResult.UpToDate(currentVersion)
        }

        val named = metadata.assets.map { asset ->
            NamedReleaseAsset(
                fileName = asset.fileName,
                downloadUrl = asset.url,
                sha256 = asset.sha256,
                sizeBytes = asset.sizeBytes,
                platform = asset.platform,
                architecture = asset.arch,
                role = asset.role,
            )
        }
        val preferAutomatic = installation.supportsAutomaticUpdate
        val selected = selectFromMetadata(named, platform, architecture, preferAutomatic)
            ?: return UpdateCheckResult.Failed(
                "No ${platform.metadataName}/${architecture.token} update artifact was listed in ${metadata.tag}",
            )

        return UpdateCheckResult.Available(
            toCandidate(
                version = metadata.version,
                releaseUrl = releaseUrl,
                asset = selected,
                platform = platform,
                architecture = architecture,
                installation = installation,
            ),
        )
    }

    private fun selectFromMetadata(
        assets: List<NamedReleaseAsset>,
        platform: DesktopPlatform,
        architecture: CpuArchitecture,
        preferAutomatic: Boolean,
    ): NamedReleaseAsset? {
        val platformAssets = assets.filter { asset ->
            asset.platform.equals(platform.metadataName, ignoreCase = true) ||
                UpdateArtifactMatcher.matchesPlatform(asset.fileName, platform)
        }.filter { asset ->
            val archToken = asset.architecture?.lowercase()
            when {
                archToken.isNullOrBlank() || archToken == "unknown" ->
                    UpdateArtifactMatcher.matchesArchitecture(asset.fileName, architecture)
                architecture == CpuArchitecture.UNKNOWN -> true
                architecture == CpuArchitecture.ARM64 ->
                    archToken in setOf("arm64", "aarch64")
                architecture == CpuArchitecture.X64 ->
                    archToken in setOf("x64", "amd64", "x86_64")
                else -> false
            }
        }
        if (platformAssets.isEmpty()) return null

        fun role(asset: NamedReleaseAsset): UpdateArtifactRole =
            when (asset.role?.lowercase()) {
                "update", "update_package", "package" -> UpdateArtifactRole.UpdatePackage
                "installer" -> UpdateArtifactRole.Installer
                else -> UpdateArtifactMatcher.roleOf(asset.fileName)
            }

        val updatePackages = platformAssets.filter { role(it) == UpdateArtifactRole.UpdatePackage }
        val installers = platformAssets.filter { role(it) == UpdateArtifactRole.Installer }
        return if (preferAutomatic) {
            UpdateArtifactMatcher.selectAsset(updatePackages.ifEmpty { platformAssets }, platform, architecture, preferAutomatic = true)
                ?: UpdateArtifactMatcher.selectAsset(installers, platform, architecture, preferAutomatic = false)
        } else {
            UpdateArtifactMatcher.selectAsset(installers.ifEmpty { platformAssets }, platform, architecture, preferAutomatic = false)
                ?: UpdateArtifactMatcher.selectAsset(updatePackages, platform, architecture, preferAutomatic = true)
        }
    }
}

internal object ReleaseUpdateSelector {
    fun select(
        release: GitHubRelease,
        currentVersion: String,
        platform: DesktopPlatform,
        architecture: CpuArchitecture = CpuArchitecture.UNKNOWN,
        installation: InstallationInfo = InstallationInfo(
            platform = platform,
            architecture = architecture,
            kind = InstallationKind.UNSUPPORTED,
            installRoot = null,
            relaunchCommand = emptyList(),
        ),
    ): UpdateCheckResult {
        if (!VersionComparator.isNewer(release.version, currentVersion)) {
            return UpdateCheckResult.UpToDate(currentVersion)
        }

        val named = release.assets.map { NamedReleaseAsset(it.name, it.downloadUrl) }
        val selected = UpdateArtifactMatcher.selectAsset(
            assets = named,
            platform = platform,
            architecture = architecture,
            preferAutomatic = installation.supportsAutomaticUpdate,
        ) ?: return UpdateCheckResult.Failed(
            "No ${platform.metadataName} installer was attached to ${release.version}",
        )

        return UpdateCheckResult.Available(
            toCandidate(
                version = release.version.removePrefix("v").removePrefix("V"),
                releaseUrl = release.releaseUrl,
                asset = selected,
                platform = platform,
                architecture = architecture,
                installation = installation,
            ),
        )
    }
}

private fun toCandidate(
    version: String,
    releaseUrl: String,
    asset: NamedReleaseAsset,
    platform: DesktopPlatform,
    architecture: CpuArchitecture,
    installation: InstallationInfo,
): UpdateCandidate {
    val role = when (asset.role?.lowercase()) {
        "update", "update_package", "package" -> UpdateArtifactRole.UpdatePackage
        "installer" -> UpdateArtifactRole.Installer
        else -> UpdateArtifactMatcher.roleOf(asset.fileName)
    }
    val detectedArch = asset.architecture?.let {
        when (it.lowercase()) {
            "arm64", "aarch64" -> CpuArchitecture.ARM64
            "x64", "amd64", "x86_64" -> CpuArchitecture.X64
            else -> null
        }
    } ?: UpdateArtifactMatcher.architectureOf(asset.fileName).takeIf { it != CpuArchitecture.UNKNOWN }
        ?: architecture

    val installMode = when {
        role == UpdateArtifactRole.UpdatePackage && installation.supportsAutomaticUpdate ->
            UpdateInstallMode.Automatic
        else -> UpdateInstallMode.Manual
    }

    return UpdateCandidate(
        version = version,
        releaseUrl = releaseUrl,
        assetName = asset.fileName,
        downloadUrl = asset.downloadUrl,
        sha256 = asset.sha256,
        sizeBytes = asset.sizeBytes,
        platform = platform,
        architecture = detectedArch,
        role = role,
        installMode = installMode,
    )
}

internal object GitHubReleaseJsonParser {
    fun parse(json: String): GitHubRelease? {
        val root = runCatching { Json.parse(json) as? JsonObject }.getOrNull() ?: return null
        val version = root.string("tag_name") ?: return null
        val releaseUrl = root.string("html_url") ?: return null
        val assets = root.array("assets")?.values.orEmpty().filterIsInstance<JsonObject>().mapNotNull { asset ->
            GitHubReleaseAsset(
                name = asset.string("name") ?: return@mapNotNull null,
                downloadUrl = asset.string("browser_download_url") ?: return@mapNotNull null,
            )
        }
        return GitHubRelease(version, releaseUrl, assets)
    }
}

internal object ReleaseUpdateMetadataJsonParser {
    private val sha256Pattern = Regex("[a-f0-9]{64}")

    fun parse(json: String): ReleaseUpdateMetadata? {
        val root = runCatching { Json.parse(json) as? JsonObject }.getOrNull() ?: return null
        val schemaVersion = root.long("schemaVersion") ?: return null
        if (schemaVersion !in 1L..2L) return null
        val version = root.string("version") ?: return null
        val tag = root.string("tag") ?: return null
        val assets = root.array("assets")?.values ?: return null
        val parsedAssets = assets.filterIsInstance<JsonObject>().mapNotNull { asset ->
            val sha256 = asset.string("sha256")?.lowercase() ?: return@mapNotNull null
            if (!sha256Pattern.matches(sha256)) return@mapNotNull null
            ReleaseUpdateAsset(
                platform = asset.string("platform") ?: return@mapNotNull null,
                fileName = asset.string("fileName") ?: return@mapNotNull null,
                url = asset.string("url") ?: return@mapNotNull null,
                sha256 = sha256,
                sizeBytes = asset.long("size")?.takeIf { it >= 0 } ?: return@mapNotNull null,
                arch = asset.string("arch"),
                role = asset.string("role"),
            )
        }
        if (parsedAssets.isEmpty()) return null
        return ReleaseUpdateMetadata(schemaVersion.toInt(), version, tag, parsedAssets)
    }
}
