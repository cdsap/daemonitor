package io.github.cdsap.daemonitor.update

import io.github.cdsap.daemonitor.Defaults
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.outputStream

data class StagedUpdate(
    val candidate: UpdateCandidate,
    val artifactPath: Path,
    val payloadPath: Path,
    val installation: InstallationInfo,
)

fun interface UpdateInstaller {
    suspend fun prepare(candidate: UpdateCandidate, onProgress: (Double?) -> Unit): StagedUpdate?
}

class DesktopUpdateInstaller(
    private val updateDirectory: Path = Defaults.APP_SUPPORT_DIR.resolve("updates"),
    private val installation: InstallationInfo = InstallationLocator.current(),
    private val opener: (Path) -> Unit = ::openWithDesktop,
    private val downloader: suspend (UpdateCandidate, Path, (Double?) -> Unit) -> Path = ::downloadAndVerify,
) : UpdateInstaller {
    override suspend fun prepare(candidate: UpdateCandidate, onProgress: (Double?) -> Unit): StagedUpdate? {
        validateCandidate(candidate)
        val artifact = downloader(candidate, updateDirectory, onProgress)
        return when (candidate.installMode) {
            UpdateInstallMode.Automatic -> {
                val payload = extractUpdatePayload(candidate, artifact, updateDirectory)
                StagedUpdate(
                    candidate = candidate,
                    artifactPath = artifact,
                    payloadPath = payload,
                    installation = installation,
                )
            }
            UpdateInstallMode.Manual -> {
                opener(artifact)
                null
            }
        }
    }

    private fun validateCandidate(candidate: UpdateCandidate) {
        require(candidate.platform == DesktopPlatform.UNKNOWN || candidate.platform == installation.platform) {
            "Update artifact platform ${candidate.platform} does not match ${installation.platform}"
        }
        if (candidate.architecture != CpuArchitecture.UNKNOWN &&
            installation.architecture != CpuArchitecture.UNKNOWN
        ) {
            require(candidate.architecture == installation.architecture) {
                "Update artifact architecture ${candidate.architecture.token} does not match ${installation.architecture.token}"
            }
        }
        if (candidate.installMode == UpdateInstallMode.Automatic) {
            require(installation.supportsAutomaticUpdate) {
                installation.manualUpdateReason ?: "Automatic updates are not supported for this installation"
            }
            require(candidate.role == UpdateArtifactRole.UpdatePackage) {
                "Automatic updates require a zip/tar.gz update package"
            }
        }
    }
}

fun interface UpdateApplier {
    fun applyAfterExit(staged: StagedUpdate)
}

class DesktopUpdateApplier(
    private val processId: Long = ProcessHandle.current().pid(),
    private val processStarter: (List<String>, Path) -> Unit = ::startDetached,
) : UpdateApplier {
    override fun applyAfterExit(staged: StagedUpdate) {
        val installRoot = staged.installation.installRoot
            ?: error("Installation location is unknown; refusing to apply the update")
        require(staged.installation.supportsAutomaticUpdate) {
            staged.installation.manualUpdateReason ?: "Automatic updates are not supported"
        }
        require(Files.exists(staged.payloadPath)) {
            "Staged update payload is missing; the application may have been moved"
        }
        require(Files.exists(installRoot)) {
            "Installation location no longer exists; refusing to apply the update"
        }

        val helper = when (staged.installation.platform) {
            DesktopPlatform.MACOS -> writeUnixHelper(staged, installRoot, mac = true)
            DesktopPlatform.LINUX -> writeUnixHelper(staged, installRoot, mac = false)
            DesktopPlatform.WINDOWS -> writeWindowsHelper(staged, installRoot)
            DesktopPlatform.UNKNOWN -> error("Unsupported platform")
        }

        val command = when (staged.installation.platform) {
            DesktopPlatform.WINDOWS -> listOf("cmd.exe", "/c", helper.toString())
            else -> listOf("/bin/bash", helper.toString())
        }
        processStarter(command, helper.parent)
    }

    private fun writeUnixHelper(staged: StagedUpdate, installRoot: Path, mac: Boolean): Path {
        val helper = staged.artifactPath.parent.resolve("apply-update.sh")
        val relaunch = staged.installation.relaunchCommand.joinToString(" ") { shellEscape(it) }
        val quarantine = if (mac) {
            "xattr -dr com.apple.quarantine \"\$app_path\" >/dev/null 2>&1 || true"
        } else {
            "true"
        }
        val stagedDir = staged.artifactPath.parent.resolve("staged")
        val script = """
            #!/bin/bash
            set -euo pipefail
            pid=$processId
            app_path=${shellEscape(installRoot.toString())}
            staged=${shellEscape(staged.payloadPath.toString())}
            backup="${"$"}{app_path}.pre-update"
            while kill -0 "${"$"}pid" 2>/dev/null; do sleep 0.2; done
            sleep 0.4
            if [[ ! -e "${"$"}app_path" || ! -e "${"$"}staged" ]]; then
              echo "Update apply aborted: installation or staged payload missing" >&2
              exit 1
            fi
            rm -rf "${"$"}backup"
            mv "${"$"}app_path" "${"$"}backup"
            if ! mv "${"$"}staged" "${"$"}app_path"; then
              mv "${"$"}backup" "${"$"}app_path"
              echo "Update apply failed; restored the previous installation" >&2
              exit 1
            fi
            $quarantine
            $relaunch
            rm -rf "${"$"}backup"
            rm -rf ${shellEscape(stagedDir.toString())}
            rm -f ${shellEscape(staged.artifactPath.toString())}
            rm -f "${"$"}0"
        """.trimIndent()
        helper.writeExecutable(script)
        return helper
    }

    private fun writeWindowsHelper(staged: StagedUpdate, installRoot: Path): Path {
        val helper = staged.artifactPath.parent.resolve("apply-update.cmd")
        val relaunch = staged.installation.relaunchCommand.joinToString(" ") { windowsQuote(it) }
        val stagedDir = staged.artifactPath.parent.resolve("staged")
        Files.writeString(
            helper,
            """
            @echo off
            setlocal
            set PID=$processId
            set APP_PATH=${windowsQuote(installRoot.toString())}
            set STAGED=${windowsQuote(staged.payloadPath.toString())}
            set BACKUP=%APP_PATH%.pre-update
            :wait
            tasklist /FI "PID eq %PID%" 2>NUL | find "%PID%" >NUL
            if not errorlevel 1 (
              timeout /t 1 /nobreak >NUL
              goto wait
            )
            if not exist "%APP_PATH%" exit /b 1
            if not exist "%STAGED%" exit /b 1
            if exist "%BACKUP%" rmdir /s /q "%BACKUP%"
            move /Y "%APP_PATH%" "%BACKUP%"
            if errorlevel 1 exit /b 1
            move /Y "%STAGED%" "%APP_PATH%"
            if errorlevel 1 (
              move /Y "%BACKUP%" "%APP_PATH%"
              exit /b 1
            )
            start "" $relaunch
            rmdir /s /q "%BACKUP%"
            rmdir /s /q ${windowsQuote(stagedDir.toString())}
            del /f /q ${windowsQuote(staged.artifactPath.toString())}
            del /f /q "%~f0"
            """.trimIndent(),
        )
        return helper
    }
}

private fun startDetached(command: List<String>, workingDir: Path?) {
    val builder = ProcessBuilder(command)
    if (workingDir != null) builder.directory(workingDir.toFile())
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.start()
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
    require(candidate.downloadUrl.startsWith("https://github.com/cdsap/daemonitor/")) {
        "Refusing to download an update from an unexpected host"
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
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
    onProgress(1.0)
    finalPath
}

internal fun extractUpdatePayload(
    candidate: UpdateCandidate,
    artifact: Path,
    updateDirectory: Path,
): Path {
    val extractRoot = updateDirectory.resolve("staged").resolve(candidate.version)
    if (Files.exists(extractRoot)) {
        extractRoot.toFile().deleteRecursively()
    }
    Files.createDirectories(extractRoot)

    val lower = candidate.assetName.lowercase()
    when {
        lower.endsWith(".zip") -> unzip(artifact, extractRoot)
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> untarGzip(artifact, extractRoot)
        else -> error("Unsupported update package format: ${candidate.assetName}")
    }

    return findPayload(extractRoot, candidate.platform)
        ?: error("Update package did not contain a Daemonitor application payload")
}

private fun findPayload(extractRoot: Path, platform: DesktopPlatform): Path? {
    Files.walk(extractRoot).use { stream ->
        val matches = stream.filter { path ->
            when (platform) {
                DesktopPlatform.MACOS ->
                    path.fileName?.toString() == "Daemonitor.app" && Files.isDirectory(path)
                DesktopPlatform.WINDOWS ->
                    Files.isDirectory(path) && Files.isRegularFile(path.resolve("Daemonitor.exe"))
                DesktopPlatform.LINUX ->
                    Files.isDirectory(path) && (
                        Files.isRegularFile(path.resolve("bin").resolve("Daemonitor")) ||
                            Files.isRegularFile(path.resolve("Daemonitor"))
                        )
                DesktopPlatform.UNKNOWN -> false
            }
        }.toList()
        return matches.minByOrNull { it.nameCount }
    }
}

private fun unzip(archive: Path, target: Path) {
    ZipInputStream(Files.newInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val outPath = target.resolve(entry.name).normalize()
            require(outPath.startsWith(target)) { "Refusing to extract path outside staging directory" }
            if (entry.isDirectory) {
                Files.createDirectories(outPath)
            } else {
                Files.createDirectories(outPath.parent)
                Files.copy(zip, outPath, StandardCopyOption.REPLACE_EXISTING)
            }
            zip.closeEntry()
        }
    }
}

private fun untarGzip(archive: Path, target: Path) {
    val result = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString())
        .redirectErrorStream(true)
        .start()
    val output = result.inputStream.bufferedReader().use { it.readText() }
    check(result.waitFor() == 0) { "Failed to extract update archive: $output" }
}

private fun Path.writeExecutable(contents: String) {
    Files.writeString(this, contents)
    toFile().setExecutable(true, false)
}

private fun shellEscape(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun windowsQuote(value: String): String =
    "\"${value.replace("\"", "\\\"")}\""

internal object UpdateDownloadVerifier {
    fun matches(actualDigest: ByteArray, expectedSha256: String): Boolean =
        actualDigest.toHex().equals(expectedSha256, ignoreCase = true)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
