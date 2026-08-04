package io.github.cdsap.daemonitor.update

import io.github.cdsap.daemonitor.Defaults
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.outputStream

fun interface UpdateInstaller {
    suspend fun open(candidate: UpdateCandidate, onProgress: (Double?) -> Unit)
}

class DesktopUpdateInstaller(
    private val updateDirectory: Path = Defaults.APP_SUPPORT_DIR.resolve("updates"),
    private val opener: (Path) -> Unit = ::openWithDesktop,
    private val downloader: suspend (UpdateCandidate, Path, (Double?) -> Unit) -> Path = ::downloadAndVerify,
) : UpdateInstaller {
    override suspend fun open(candidate: UpdateCandidate, onProgress: (Double?) -> Unit) {
        val installer = downloader(candidate, updateDirectory, onProgress)
        opener(installer)
    }
}

private fun openWithDesktop(path: Path) {
    require(Desktop.isDesktopSupported()) { "Desktop integration is not available" }
    val desktop = Desktop.getDesktop()
    require(desktop.isSupported(Desktop.Action.OPEN)) { "Opening downloaded installers is not supported" }
    desktop.open(path.toFile())
}

private suspend fun downloadAndVerify(
    candidate: UpdateCandidate,
    updateDirectory: Path,
    onProgress: (Double?) -> Unit,
): Path = withContext(Dispatchers.IO) {
    val expectedSha256 = candidate.sha256
        ?: error("Release checksum is unavailable; refusing to install ${candidate.assetName}")
    require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
        "Release checksum is invalid for ${candidate.assetName}"
    }

    Files.createDirectories(updateDirectory)
    val tempPath = updateDirectory.resolve("${candidate.assetName}.download")
    val finalPath = updateDirectory.resolve(candidate.assetName)
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val request = HttpRequest.newBuilder()
        .uri(URI(candidate.downloadUrl))
        .header("User-Agent", "Daemonitor")
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() !in 200..299) {
        error("Download failed with HTTP ${response.statusCode()}")
    }

    val expectedBytes = candidate.sizeBytes
        ?: response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf { it > 0 }
    val digest = MessageDigest.getInstance("SHA-256")
    var downloadedBytes = 0L
    response.body().use { input ->
        tempPath.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                downloadedBytes += read
                onProgress(expectedBytes?.let { downloadedBytes.toDouble() / it.toDouble() })
            }
        }
    }

    if (expectedBytes != null && downloadedBytes != expectedBytes) {
        Files.deleteIfExists(tempPath)
        error("Downloaded ${downloadedBytes} bytes; expected ${expectedBytes}")
    }
    if (!UpdateDownloadVerifier.matches(digest.digest(), expectedSha256)) {
        Files.deleteIfExists(tempPath)
        error("Downloaded installer checksum did not match release metadata")
    }
    Files.move(
        tempPath,
        finalPath,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
    onProgress(1.0)
    finalPath
}

internal object UpdateDownloadVerifier {
    fun matches(actualDigest: ByteArray, expectedSha256: String): Boolean =
        actualDigest.toHex().equals(expectedSha256, ignoreCase = true)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
