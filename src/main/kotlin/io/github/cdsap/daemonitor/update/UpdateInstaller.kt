package io.github.cdsap.daemonitor.update

import io.github.cdsap.daemonitor.platform.AppDirectories
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

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
    private val updateDirectory: Path = AppDirectories.system.updatesDirectory,
    private val installation: InstallationInfo = InstallationLocator.current(),
    private val opener: (Path) -> Unit = ::openWithDesktop,
    private val downloader: suspend (UpdateCandidate, Path, (Double?) -> Unit) -> Path = ::downloadAndVerify,
) : UpdateInstaller {
    override suspend fun prepare(candidate: UpdateCandidate, onProgress: (Double?) -> Unit): StagedUpdate? {
        validateUpdatePaths(candidate)
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
            DesktopPlatform.WINDOWS -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", helper.toString(),
            )
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
            # Clear leftovers from older helpers that kept a sibling .pre-update bundle.
            rm -rf "${"$"}{app_path}.pre-update"
            while kill -0 "${"$"}pid" 2>/dev/null; do sleep 0.2; done
            sleep 0.4
            if [[ ! -e "${"$"}app_path" || ! -e "${"$"}staged" ]]; then
              echo "Update apply aborted: installation or staged payload missing" >&2
              exit 1
            fi
            backup_root="${"$"}(mktemp -d "${"$"}{TMPDIR:-/tmp}/daemonitor-pre-update.XXXXXX")"
            backup="${"$"}{backup_root}/previous"
            mv "${"$"}app_path" "${"$"}backup"
            if ! mv "${"$"}staged" "${"$"}app_path"; then
              mv "${"$"}backup" "${"$"}app_path"
              rm -rf "${"$"}backup_root"
              echo "Update apply failed; restored the previous installation" >&2
              exit 1
            fi
            cleanup_backup() {
              rm -rf "${"$"}backup_root"
            }
            trap cleanup_backup EXIT
            # Remove the rollback backup before relaunch so a failed open/start cannot
            # leave a previous bundle behind.
            rm -rf "${"$"}backup_root"
            $quarantine
            $relaunch
            rm -rf ${shellEscape(stagedDir.toString())}
            rm -f ${shellEscape(staged.artifactPath.toString())}
            rm -f "${"$"}0"
        """.trimIndent()
        helper.writeExecutable(script)
        return helper
    }

    private fun writeWindowsHelper(staged: StagedUpdate, installRoot: Path): Path {
        val helper = safeChild(staged.artifactPath.parent, "apply-update.ps1")
        val backupRoot = Files.createTempDirectory(staged.artifactPath.parent, "rollback-")
        val relaunch = staged.installation.relaunchCommand
        require(relaunch.isNotEmpty()) { "Relaunch command is missing" }
        val arguments = relaunch.drop(1).joinToString(", ") { powershellLiteral(windowsArgument(it)) }
        Files.writeString(
            helper,
            "\uFEFF" + """
            ${"$"}ErrorActionPreference = 'Stop'
            ${"$"}appPath = ${powershellLiteral(installRoot.toString())}
            ${"$"}staged = ${powershellLiteral(staged.payloadPath.toString())}
            ${"$"}backupRoot = ${powershellLiteral(backupRoot.toString())}
            ${"$"}backup = Join-Path ${"$"}backupRoot 'previous'
            ${"$"}legacyBackup = ${"$"}appPath + '.pre-update'
            if (Test-Path -LiteralPath ${"$"}legacyBackup) { Remove-Item -LiteralPath ${"$"}legacyBackup -Recurse -Force }
            Wait-Process -Id $processId -ErrorAction SilentlyContinue
            if (!(Test-Path -LiteralPath ${"$"}appPath) -or !(Test-Path -LiteralPath ${"$"}staged)) { exit 1 }
            Move-Item -LiteralPath ${"$"}appPath -Destination ${"$"}backup
            try {
                Move-Item -LiteralPath ${"$"}staged -Destination ${"$"}appPath
            } catch {
                Move-Item -LiteralPath ${"$"}backup -Destination ${"$"}appPath
                throw
            }
            Remove-Item -LiteralPath ${"$"}backupRoot -Recurse -Force
            Start-Process -FilePath ${powershellLiteral(relaunch.first())}${if (arguments.isEmpty()) "" else " -ArgumentList @($arguments)"}
            Remove-Item -LiteralPath ${powershellLiteral(staged.artifactPath.parent.resolve("staged").toString())} -Recurse -Force
            Remove-Item -LiteralPath ${powershellLiteral(staged.artifactPath.toString())} -Force
            Remove-Item -LiteralPath ${"$"}PSCommandPath -Force
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
    validateUpdatePaths(candidate)
    val expectedSha256 = candidate.sha256
        ?: error("Release checksum is unavailable; refusing to install ${candidate.assetName}")
    require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
        "Release checksum is invalid for ${candidate.assetName}"
    }
    require(candidate.sizeBytes == null || candidate.sizeBytes in 1..MAX_DOWNLOAD_BYTES) {
        "Update download exceeds size limit"
    }
    val downloadUri = URI(candidate.downloadUrl)
    require(downloadUri.host == "github.com" && downloadUri.path.startsWith("/cdsap/daemonitor/")) {
        "Refusing update download outside the Daemonitor repository"
    }
    Files.createDirectories(updateDirectory)
    val tempPath = safeChild(updateDirectory, "${candidate.assetName}.download")
    val finalPath = safeChild(updateDirectory, candidate.assetName)
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val response = fetchGitHubAsset(client, candidate.downloadUrl, "application/octet-stream")
    val digest = MessageDigest.getInstance("SHA-256")
    var downloadedBytes = 0L
    try {
        response.body().use { input ->
            val expectedBytes = candidate.sizeBytes
                ?: response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf { it > 0 }
            require(expectedBytes == null || expectedBytes in 1..MAX_DOWNLOAD_BYTES) { "Update download exceeds size limit" }
            Files.newOutputStream(tempPath, CREATE_NEW).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(downloadedBytes <= MAX_DOWNLOAD_BYTES - read) { "Update download exceeds size limit" }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    downloadedBytes += read
                    onProgress(expectedBytes?.let { downloadedBytes.toDouble() / it.toDouble() })
                }
            }
            if (expectedBytes != null && downloadedBytes != expectedBytes) {
                error("Downloaded ${downloadedBytes} bytes; expected ${expectedBytes}")
            }
        }
        if (!UpdateDownloadVerifier.matches(digest.digest(), expectedSha256)) {
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
    } finally {
        Files.deleteIfExists(tempPath)
    }
}

internal fun extractUpdatePayload(
    candidate: UpdateCandidate,
    artifact: Path,
    updateDirectory: Path,
): Path {
    validateUpdatePaths(candidate)
    val stagedRoot = safeChild(updateDirectory, "staged")
    val extractRoot = safeChild(stagedRoot, candidate.version)
    if (Files.exists(extractRoot)) {
        // walkFileTree does not follow symlinks, including ones left in an old staging tree.
        Files.walkFileTree(extractRoot, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
    Files.createDirectories(extractRoot)

    val lower = candidate.assetName.lowercase()
    when {
        lower.endsWith(".zip") -> unzip(artifact, extractRoot)
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> untarGzip(artifact, extractRoot)
        else -> error("Unsupported update package format: ${candidate.assetName}")
    }

    val payload = findPayload(extractRoot, candidate.platform)
        ?: error("Update package did not contain a Daemonitor application payload")
    if (candidate.platform == DesktopPlatform.MACOS) {
        restoreMacAppExecutables(payload)
    }
    return payload
}

private fun restoreMacAppExecutables(app: Path) {
    listOf(
        app.resolve("Contents/MacOS/Daemonitor"),
        app.resolve("Contents/runtime/Contents/Home/bin/java"),
        app.resolve("Contents/runtime/Contents/Home/lib/jspawnhelper"),
        app.resolve("Contents/app/resources/daemonitor-cored"),
    ).filter(Files::isRegularFile).forEach { executable ->
        executable.toFile().setExecutable(true, false)
    }
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

private const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024
private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 100_000

private fun validateUpdatePaths(candidate: UpdateCandidate) {
    require(candidate.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+([.-][0-9A-Za-z.-]+)?"))) {
        "Invalid update version"
    }
    require(candidate.assetName.matches(Regex("[A-Za-z0-9._-]+")) && candidate.assetName !in listOf(".", "..")) {
        "Invalid update asset filename"
    }
}

private fun safeChild(base: Path, name: String): Path {
    val root = base.toAbsolutePath().normalize()
    val child = root.resolve(name).normalize()
    require(child.startsWith(root) && child != root) { "Refusing path outside update directory" }
    var path: Path? = child
    while (path != null) {
        require(!Files.isSymbolicLink(path)) { "Refusing symbolic link in update path" }
        path = path.parent
        if (path == root.parent) break
    }
    return child
}

private fun archivePath(target: Path, name: String): Path {
    require(name.isNotEmpty() && !name.startsWith("/") && !name.contains('\\') && !name.contains(':') &&
        name.split('/').none { it == ".." }
    ) { "Refusing unsafe archive path" }
    // A tar created from '.' may have a harmless root directory entry.
    if (name.trimEnd('/') == ".") return target
    return safeChild(target, name)
}

private class ExtractionBudget {
    private var remaining = MAX_EXTRACTED_BYTES
    private var entries = 0

    fun next() {
        require(++entries <= MAX_ARCHIVE_ENTRIES) { "Update archive has too many entries" }
    }

    fun copy(input: java.io.InputStream, path: Path) {
        Files.createDirectories(path.parent)
        Files.newOutputStream(path, CREATE_NEW).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= remaining) { "Update archive exceeds extracted size limit" }
                remaining -= count
                output.write(buffer, 0, count)
            }
        }
    }
}

private fun unzip(archive: Path, target: Path) {
    val budget = ExtractionBudget()
    ZipInputStream(Files.newInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            budget.next()
            val outPath = archivePath(target, entry.name)
            if (entry.isDirectory) {
                Files.createDirectories(outPath)
                require(zip.read() == -1) { "Refusing zip directory containing data" }
            } else {
                budget.copy(zip, outPath)
            }
            zip.closeEntry()
        }
    }
}

// Include tar headers and PAX/GNU metadata in the expansion budget, before the
// archive reader can allocate memory for an extended name or metadata entry.
private class ExpandedTarInputStream(input: java.io.InputStream) : java.io.FilterInputStream(input) {
    private var remaining = MAX_EXTRACTED_BYTES

    override fun read(): Int {
        val value = `in`.read()
        if (value >= 0) {
            require(remaining > 0) { "Update archive exceeds extracted size limit" }
            remaining--
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = `in`.read(buffer, offset, minOf(length.toLong(), remaining + 1).toInt())
        if (count > 0) {
            require(count <= remaining) { "Update archive exceeds extracted size limit" }
            remaining -= count
        }
        return count
    }

    override fun skip(count: Long): Long {
        // FilterInputStream.skip would bypass accounting.
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var skipped = 0L
        while (skipped < count) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }
}

private fun untarGzip(archive: Path, target: Path) {
    val budget = ExtractionBudget()
    TarArchiveInputStream(ExpandedTarInputStream(GZIPInputStream(Files.newInputStream(archive)))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            budget.next()
            require(!entry.isSymbolicLink && !entry.isLink && !entry.isSparse &&
                (entry.isDirectory || entry.isFile) && tar.canReadEntryData(entry)
            ) { "Refusing unsupported tar entry" }
            require(entry.size in 0..MAX_EXTRACTED_BYTES) { "Update archive exceeds extracted size limit" }
            val outPath = archivePath(target, entry.name)
            if (entry.isDirectory) {
                Files.createDirectories(outPath)
            } else {
                budget.copy(tar, outPath)
                if (entry.mode and 0b001001001 != 0) outPath.toFile().setExecutable(true, false)
            }
        }
    }
}

private fun Path.writeExecutable(contents: String) {
    Files.writeString(this, contents)
    toFile().setExecutable(true, false)
}

private fun shellEscape(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

// Windows native argument quoting; PowerShell receives this as data, never cmd source.
private fun windowsArgument(value: String): String {
    val escaped = Regex("(\\\\*)\"").replace(value) { match ->
        "\\".repeat(match.groupValues[1].length * 2 + 1) + "\""
    }
    val trailingSlashes = escaped.takeLastWhile { it == '\\' }.length
    return "\"" + escaped + "\\".repeat(trailingSlashes) + "\""
}

private fun powershellLiteral(value: String): String =
    "'" + value.replace("'", "''") + "'"

internal object UpdateDownloadVerifier {
    fun matches(actualDigest: ByteArray, expectedSha256: String): Boolean =
        actualDigest.toHex().equals(expectedSha256, ignoreCase = true)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
