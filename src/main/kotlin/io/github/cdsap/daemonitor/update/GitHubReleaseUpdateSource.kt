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
            ReleaseUpdateSelector.select(release, currentVersion, platform)
        }.getOrElse { error ->
            UpdateCheckResult.Failed(error.message ?: error::class.simpleName ?: "Update check failed")
        }
    }
}

internal data class GitHubRelease(
    val version: String,
    val releaseUrl: String,
    val assets: List<GitHubReleaseAsset>,
)

internal data class GitHubReleaseAsset(val name: String, val downloadUrl: String)

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
