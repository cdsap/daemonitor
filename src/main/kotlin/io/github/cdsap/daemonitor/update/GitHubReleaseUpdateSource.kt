package io.github.cdsap.daemonitor.update

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GitHubReleaseUpdateSource(
    private val repository: String = "cdsap/daemonitor",
    private val platform: DesktopPlatform = DesktopPlatform.current(),
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (platform == DesktopPlatform.UNKNOWN) {
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
                ReleaseMetadataUpdateSelector.select(metadata, release.releaseUrl, currentVersion, platform)
            } else {
                ReleaseUpdateSelector.select(release, currentVersion, platform)
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
)

internal object ReleaseMetadataUpdateSelector {
    fun select(
        metadata: ReleaseUpdateMetadata,
        releaseUrl: String,
        currentVersion: String,
        platform: DesktopPlatform,
    ): UpdateCheckResult {
        val asset = metadata.assets.firstOrNull { it.platform == platform.metadataName }
            ?: return UpdateCheckResult.Failed("No ${platform.name.lowercase()} installer was listed in ${metadata.tag}")

        return if (VersionComparator.isNewer(metadata.version, currentVersion)) {
            UpdateCheckResult.Available(
                UpdateCandidate(
                    version = metadata.version,
                    releaseUrl = releaseUrl,
                    assetName = asset.fileName,
                    downloadUrl = asset.url,
                    sha256 = asset.sha256,
                    sizeBytes = asset.sizeBytes,
                ),
            )
        } else {
            UpdateCheckResult.UpToDate(currentVersion)
        }
    }

    private val DesktopPlatform.metadataName: String
        get() = when (this) {
            DesktopPlatform.MACOS -> "macos"
            DesktopPlatform.WINDOWS -> "windows"
            DesktopPlatform.LINUX -> "linux"
            DesktopPlatform.UNKNOWN -> "unknown"
        }
}

internal object ReleaseUpdateSelector {
    fun select(
        release: GitHubRelease,
        currentVersion: String,
        platform: DesktopPlatform,
    ): UpdateCheckResult {
        val asset = release.assets.firstOrNull { it.name.endsWith(platform.assetSuffix) }
            ?: return UpdateCheckResult.Failed("No ${platform.name.lowercase()} installer was attached to ${release.version}")

        return if (VersionComparator.isNewer(release.version, currentVersion)) {
            UpdateCheckResult.Available(
                UpdateCandidate(
                    version = release.version.removePrefix("v").removePrefix("V"),
                    releaseUrl = release.releaseUrl,
                    assetName = asset.name,
                    downloadUrl = asset.downloadUrl,
                ),
            )
        } else {
            UpdateCheckResult.UpToDate(currentVersion)
        }
    }
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
        if (schemaVersion != 1) return null
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
            )
        }.toList()
        if (assets.isEmpty()) return null
        return ReleaseUpdateMetadata(version = version, tag = tag, assets = assets)
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
