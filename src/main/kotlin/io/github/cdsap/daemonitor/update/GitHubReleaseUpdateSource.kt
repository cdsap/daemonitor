package io.github.cdsap.daemonitor.update

import io.github.cdsap.daemonitor.application.update.UpdateSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

        runCatching {
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
        }.getOrElse { error ->
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
    private val tagPattern = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val releaseUrlPattern = Regex(""""html_url"\s*:\s*"([^"]+)"""")
    private val assetPattern = Regex(
        """"name"\s*:\s*"([^"]+)".*?"browser_download_url"\s*:\s*"([^"]+)"""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(json: String): GitHubRelease? {
        val version = tagPattern.find(json)?.groupValues?.get(1) ?: return null
        val releaseUrl = releaseUrlPattern.find(json)?.groupValues?.get(1) ?: return null
        val assetsJson = json.substringAfter(""""assets"""", missingDelimiterValue = "")
        val assets = assetPattern.findAll(assetsJson).map { match ->
            GitHubReleaseAsset(
                name = match.groupValues[1].jsonUnescape(),
                downloadUrl = match.groupValues[2].jsonUnescape(),
            )
        }.toList()
        return GitHubRelease(version = version, releaseUrl = releaseUrl.jsonUnescape(), assets = assets)
    }

    private fun String.jsonUnescape(): String =
        replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}

internal object ReleaseUpdateMetadataJsonParser {
    fun parse(json: String): ReleaseUpdateMetadata? {
        val schemaVersion = json.intField("schemaVersion") ?: return null
        if (schemaVersion !in 1..2) return null
        val version = json.stringField("version") ?: return null
        val tag = json.stringField("tag") ?: return null
        val assetsJson = json.arrayField("assets") ?: return null
        val assets = assetsJson.objectValues().mapNotNull { assetJson ->
            val sha256 = assetJson.stringField("sha256")?.lowercase()
            if (sha256 == null || !sha256.matches(Regex("[a-f0-9]{64}"))) return@mapNotNull null
            ReleaseUpdateAsset(
                platform = assetJson.stringField("platform") ?: return@mapNotNull null,
                fileName = assetJson.stringField("fileName") ?: return@mapNotNull null,
                url = assetJson.stringField("url") ?: return@mapNotNull null,
                sha256 = sha256,
                sizeBytes = assetJson.longField("size") ?: return@mapNotNull null,
                arch = assetJson.stringField("arch"),
                role = assetJson.stringField("role"),
            )
        }.toList()
        if (assets.isEmpty()) return null
        return ReleaseUpdateMetadata(
            schemaVersion = schemaVersion,
            version = version,
            tag = tag,
            assets = assets,
        )
    }

    private fun String.stringField(name: String): String? {
        val valueStart = valueStart(name) ?: return null
        if (getOrNull(valueStart) != '"') return null
        val start = valueStart + 1
        var index = start
        var escaped = false
        while (index < length) {
            val char = this[index]
            if (char == '"' && !escaped) {
                return substring(start, index).jsonUnescape()
            }
            escaped = char == '\\' && !escaped
            if (char != '\\') escaped = false
            index++
        }
        return null
    }

    private fun String.intField(name: String): Int? =
        longField(name)?.toInt()

    private fun String.longField(name: String): Long? {
        val start = valueStart(name) ?: return null
        var end = start
        while (end < length && this[end].isDigit()) end++
        return substring(start, end).toLongOrNull()
    }

    private fun String.arrayField(name: String): String? {
        val valueStart = valueStart(name) ?: return null
        if (getOrNull(valueStart) != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in valueStart until length) {
            val char = this[index]
            if (char == '"' && !escaped) inString = !inString
            if (!inString) {
                if (char == '[') depth++
                if (char == ']') {
                    depth--
                    if (depth == 0) return substring(valueStart + 1, index)
                }
            }
            escaped = char == '\\' && !escaped
            if (char != '\\') escaped = false
        }
        return null
    }

    private fun String.objectValues(): List<String> {
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in indices) {
            val char = this[index]
            if (char == '"' && !escaped) inString = !inString
            if (!inString) {
                if (char == '{') {
                    if (depth == 0) start = index
                    depth++
                }
                if (char == '}') {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects += substring(start, index + 1)
                        start = -1
                    }
                }
            }
            escaped = char == '\\' && !escaped
            if (char != '\\') escaped = false
        }
        return objects
    }

    private fun String.valueStart(name: String): Int? {
        val key = """"$name""""
        val keyStart = indexOf(key)
        if (keyStart < 0) return null
        val colon = indexOf(':', startIndex = keyStart + key.length)
        if (colon < 0) return null
        var index = colon + 1
        while (index < length && this[index].isWhitespace()) index++
        return index.takeIf { it < length }
    }

    private fun String.jsonUnescape(): String =
        replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}
