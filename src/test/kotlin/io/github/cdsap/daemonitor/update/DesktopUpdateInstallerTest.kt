package io.github.cdsap.daemonitor.update

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class DesktopUpdateInstallerTest {

    @Test
    fun `downloads update package and stages payload without opening`(@TempDir tmp: Path) = runTest {
        val opened = mutableListOf<Path>()
        val progress = mutableListOf<Double?>()
        val candidate = UpdateCandidate(
            version = "1.0.7",
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.7",
            assetName = "Daemonitor-1.0.7-macos-arm64.zip",
            downloadUrl = "https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-arm64.zip",
            sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
            sizeBytes = 128,
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            role = UpdateArtifactRole.UpdatePackage,
            installMode = UpdateInstallMode.Automatic,
        )
        val zip = tmp.resolve("input.zip").also { writeMacAppZip(it) }
        val installer = DesktopUpdateInstaller(
            updateDirectory = tmp.resolve("updates"),
            installation = macInstall(),
            opener = { opened.add(it) },
            downloader = { update, directory, onProgress ->
                onProgress(0.5)
                Files.createDirectories(directory)
                directory.resolve(update.assetName).also { path ->
                    Files.copy(zip, path)
                }
            },
        )

        val staged = installer.prepare(candidate) { progress.add(it) }

        assertEquals(listOf<Double?>(0.5), progress)
        assertTrue(opened.isEmpty())
        assertNotNull(staged)
        assertEquals("Daemonitor.app", staged.payloadPath.fileName.toString())
        assertTrue(Files.isDirectory(staged.payloadPath))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `restores executable bits for macOS app launchers after zip extraction`(@TempDir tmp: Path) = runTest {
        val candidate = UpdateCandidate(
            version = "1.0.7",
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.7",
            assetName = "Daemonitor-1.0.7-macos-arm64.zip",
            downloadUrl = "https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-arm64.zip",
            sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
            sizeBytes = 128,
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            role = UpdateArtifactRole.UpdatePackage,
            installMode = UpdateInstallMode.Automatic,
        )
        val zip = tmp.resolve("input.zip").also { writeMacAppZip(it) }
        val installer = DesktopUpdateInstaller(
            updateDirectory = tmp.resolve("updates"),
            installation = macInstall(),
            downloader = { update, directory, _ ->
                Files.createDirectories(directory)
                directory.resolve(update.assetName).also { path -> Files.copy(zip, path) }
            },
        )

        val staged = installer.prepare(candidate) {}
        val app = assertNotNull(staged).payloadPath

        assertTrue(Files.isExecutable(app.resolve("Contents/MacOS/Daemonitor")))
        assertTrue(Files.isExecutable(app.resolve("Contents/runtime/Contents/Home/bin/java")))
        assertTrue(Files.isExecutable(app.resolve("Contents/runtime/Contents/Home/lib/jspawnhelper")))
        assertTrue(Files.isExecutable(app.resolve("Contents/app/resources/daemonitor-cored")))
    }

    @Test
    fun `manual mode downloads and opens local installer path`(@TempDir tmp: Path) = runTest {
        val opened = mutableListOf<Path>()
        val candidate = UpdateCandidate(
            version = "1.0.4",
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.4",
            assetName = "Daemonitor-1.0.4-macos.dmg",
            downloadUrl = "https://github.com/cdsap/daemonitor/releases/download/v1.0.4/Daemonitor-1.0.4-macos.dmg",
            sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
            sizeBytes = 128575584,
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            role = UpdateArtifactRole.Installer,
            installMode = UpdateInstallMode.Manual,
        )
        val installer = DesktopUpdateInstaller(
            updateDirectory = tmp,
            installation = macInstall(),
            opener = { opened.add(it) },
            downloader = { update, directory, onProgress ->
                onProgress(0.5)
                directory.resolve(update.assetName).also { path ->
                    Files.writeString(path, "installer")
                }
            },
        )

        val staged = installer.prepare(candidate) {}

        assertNull(staged)
        assertEquals(listOf(tmp.resolve("Daemonitor-1.0.4-macos.dmg")), opened)
    }

    @Test
    fun `rejects architecture mismatches before download`(@TempDir tmp: Path) = runTest {
        val candidate = UpdateCandidate(
            version = "1.0.7",
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.7",
            assetName = "Daemonitor-1.0.7-macos-x64.zip",
            downloadUrl = "https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-x64.zip",
            sha256 = "24fa35b9fcbe9e069c677b045b7509a01d5376b46cdb8aa655d61069575564c1",
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.X64,
            role = UpdateArtifactRole.UpdatePackage,
            installMode = UpdateInstallMode.Automatic,
        )
        val installer = DesktopUpdateInstaller(
            updateDirectory = tmp,
            installation = macInstall(),
            downloader = { _, _, _ -> error("should not download") },
        )

        val error = assertFailsWith<IllegalArgumentException> {
            installer.prepare(candidate) {}
        }
        assertTrue(error.message!!.contains("architecture"))
    }

    @Test
    fun `matches expected installer checksum`() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("installer".toByteArray())

        assertTrue(
            UpdateDownloadVerifier.matches(
                digest,
                "9c0d294c05fc1d88d698034609bb81c0c69196327594e4c69d2915c80fd9850c",
            ),
        )
    }

    @Test
    fun `apply helper is launched for staged automatic updates`(@TempDir tmp: Path) {
        val started = mutableListOf<List<String>>()
        val app = tmp.resolve("Daemonitor.app").also { Files.createDirectories(it) }
        val payload = tmp.resolve("staged/Daemonitor.app").also { Files.createDirectories(it) }
        val artifact = tmp.resolve("Daemonitor-1.0.7-macos-arm64.zip").also { Files.writeString(it, "zip") }
        val staged = StagedUpdate(
            candidate = UpdateCandidate(
                version = "1.0.7",
                releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.7",
                assetName = "Daemonitor-1.0.7-macos-arm64.zip",
                downloadUrl = "https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-arm64.zip",
                platform = DesktopPlatform.MACOS,
                architecture = CpuArchitecture.ARM64,
                role = UpdateArtifactRole.UpdatePackage,
                installMode = UpdateInstallMode.Automatic,
            ),
            artifactPath = artifact,
            payloadPath = payload,
            installation = InstallationInfo(
                platform = DesktopPlatform.MACOS,
                architecture = CpuArchitecture.ARM64,
                kind = InstallationKind.MACOS_APP_BUNDLE,
                installRoot = app,
                relaunchCommand = listOf("/usr/bin/open", "-n", app.toString()),
            ),
        )

        DesktopUpdateApplier(
            processId = 4242,
            processStarter = { command, _ -> started += command },
        ).applyAfterExit(staged)

        assertEquals(1, started.size)
        assertEquals("/bin/bash", started.single().first())
        assertTrue(Files.exists(tmp.resolve("apply-update.sh")))
        val script = Files.readString(tmp.resolve("apply-update.sh"))
        assertTrue(script.contains("pid=4242"))
        assertTrue(script.contains(app.toString()))
        assertTrue(script.contains("trap cleanup_backup EXIT"))
        assertTrue(script.contains("daemonitor-pre-update.XXXXXX"))
        assertTrue(script.contains("\${app_path}.pre-update"))
        val trapIndex = script.indexOf("trap cleanup_backup EXIT")
        val postTrapCleanup = script.indexOf("rm -rf \"\$backup_root\"", startIndex = trapIndex)
        val relaunch = script.indexOf("/usr/bin/open")
        assertTrue(trapIndex >= 0, "expected EXIT trap for backup cleanup")
        assertTrue(postTrapCleanup >= 0, "expected backup cleanup after trap")
        assertTrue(relaunch >= 0, "expected relaunch via open")
        assertTrue(postTrapCleanup < relaunch, "backup must be removed before relaunch")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `unix apply helper removes backup before relaunch and on exit`(@TempDir tmp: Path) {
        val app = tmp.resolve("Daemonitor.app").also { Files.writeString(it, "previous") }
        val leftover = tmp.resolve("Daemonitor.app.pre-update").also { Files.writeString(it, "stale") }
        val staged = tmp.resolve("staged/Daemonitor.app").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "updated")
        }
        val artifact = tmp.resolve("Daemonitor-1.0.8-linux.zip").also { Files.writeString(it, "zip") }
        val stagedUpdate = stagedUpdate(
            platform = DesktopPlatform.LINUX,
            app = app,
            staged = staged,
            artifact = artifact,
            relaunchCommand = listOf("true"),
        )
        val started = mutableListOf<List<String>>()

        DesktopUpdateApplier(
            processId = unusedProcessId(),
            processStarter = { command, _ ->
                started += command
                val process = ProcessBuilder(command).start()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            },
        ).applyAfterExit(stagedUpdate)

        assertEquals(listOf(listOf("/bin/bash", tmp.resolve("apply-update.sh").toString())), started)
        assertEquals("updated", Files.readString(app))
        assertTrue(!Files.exists(leftover))
        assertTrue(!Files.exists(tmp.resolve("Daemonitor.app.pre-update")))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `unix apply helper removes backup even when relaunch fails`(@TempDir tmp: Path) {
        val app = tmp.resolve("Daemonitor.app").also { Files.writeString(it, "previous") }
        val staged = tmp.resolve("staged/Daemonitor.app").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "updated")
        }
        val artifact = tmp.resolve("Daemonitor-1.0.8-linux.zip").also { Files.writeString(it, "zip") }
        val stagedUpdate = stagedUpdate(
            platform = DesktopPlatform.LINUX,
            app = app,
            staged = staged,
            artifact = artifact,
            relaunchCommand = listOf("false"),
        )

        DesktopUpdateApplier(
            processId = unusedProcessId(),
            processStarter = { command, _ ->
                val process = ProcessBuilder(command).start()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertTrue(process.exitValue() != 0)
            },
        ).applyAfterExit(stagedUpdate)

        assertEquals("updated", Files.readString(app))
        assertTrue(!Files.exists(tmp.resolve("Daemonitor.app.pre-update")))
    }

    @Test
    fun `windows helper uses literal paths and private backup before relaunch`(@TempDir tmp: Path) {
        val app = tmp.resolve("Daemónitor & ^ %PATH% ! $ ' [app].exe").also { Files.writeString(it, "previous") }
        val staged = tmp.resolve("staged/Daemonitor.exe").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "updated")
        }
        val artifact = tmp.resolve("Daemonitor-1.0.8-windows.zip").also { Files.writeString(it, "zip") }
        val stagedUpdate = stagedUpdate(
            platform = DesktopPlatform.WINDOWS,
            app = app,
            staged = staged,
            artifact = artifact,
            relaunchCommand = listOf(app.toString(), "arg & %PATH% ' $ | ^ !"),
        )
        val started = mutableListOf<List<String>>()

        DesktopUpdateApplier(
            processId = 4242,
            processStarter = { command, _ -> started += command },
        ).applyAfterExit(stagedUpdate)

        val script = Files.readString(tmp.resolve("apply-update.ps1"), StandardCharsets.UTF_8)
        assertTrue(script.startsWith("\uFEFF"), "Windows PowerShell needs a UTF-8 BOM for Unicode paths")
        assertTrue(script.contains("${'$'}appPath = '" + app.toString().replace("'", "''") + "'"))
        assertTrue(script.contains("'\"arg & %PATH% '' $ | ^ !\"'"))
        assertTrue(script.contains("Move-Item -LiteralPath ${'$'}appPath"))
        assertTrue(script.contains("Move-Item -LiteralPath ${'$'}backup -Destination ${'$'}appPath"))
        assertTrue(!script.contains("TEMP"))
        val backupRoot = Files.list(tmp).use { files -> files.filter { it.fileName.toString().startsWith("rollback-") }.findFirst().orElseThrow() }
        assertTrue(script.contains(backupRoot.toString()))
        assertTrue(script.indexOf("Remove-Item -LiteralPath ${'$'}backupRoot") < script.indexOf("Start-Process"))
        assertEquals(
            listOf(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", tmp.resolve("apply-update.ps1").toString())),
            started,
        )
    }

    @Test
    fun `rejects unsafe candidate paths before download`(@TempDir tmp: Path) = runTest {
        val base = stagedUpdate(DesktopPlatform.MACOS, tmp, tmp, tmp.resolve("update.zip"), emptyList()).candidate
        val installer = DesktopUpdateInstaller(
            updateDirectory = tmp,
            installation = macInstall(),
            downloader = { _, _, _ -> error("must reject before download") },
        )
        for (version in listOf("../outside", "/tmp/outside", "1.2.3/../../outside", "1.2.3\\outside")) {
            assertFailsWith<IllegalArgumentException> { installer.prepare(base.copy(version = version)) {} }
            assertFailsWith<IllegalArgumentException> { extractUpdatePayload(base.copy(version = version), tmp, tmp) }
        }
        for (name in listOf("../outside.zip", "/outside.zip", "..", "a\\b.zip", "a&b.zip")) {
            assertFailsWith<IllegalArgumentException> { installer.prepare(base.copy(assetName = name)) {} }
        }
    }

    @Test
    fun `rejects zip traversal and absolute paths`(@TempDir tmp: Path) {
        for (name in listOf("../outside", "/outside", "C:/outside", "a/../outside", "a\\outside")) {
            val archive = tmp.resolve("bad.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
            val candidate = stagedUpdate(DesktopPlatform.LINUX, tmp, tmp, archive, emptyList()).candidate
            assertFailsWith<IllegalArgumentException>(name) { extractUpdatePayload(candidate, archive, tmp.resolve("updates")) }
        }
        assertTrue(!Files.exists(tmp.resolve("updates/staged/outside")))
    }

    @Test
    fun `rejects tar traversal links and oversized entries`(@TempDir tmp: Path) {
        val entries = listOf(
            TarArchiveEntry("../outside"),
            TarArchiveEntry("/outside", true),
            TarArchiveEntry("C:/outside", true),
            TarArchiveEntry("a/../outside"),
            TarArchiveEntry("a\\outside"),
            TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply { linkName = "../../outside" },
            TarArchiveEntry("link", TarConstants.LF_LINK).apply { linkName = "../../outside" },
            TarArchiveEntry("huge").apply { size = 2L * 1024 * 1024 * 1024 + 1 },
        )
        for (entry in entries) {
            val archive = tmp.resolve("bad.tar.gz")
            // Write just the header: invalid entries must be rejected before their data is read.
            GZIPOutputStream(Files.newOutputStream(archive)).use { gzip ->
                val header = ByteArray(512)
                entry.writeEntryHeader(header)
                gzip.write(header)
                gzip.write(ByteArray(1024))
            }
            val candidate = stagedUpdate(DesktopPlatform.LINUX, tmp, tmp, archive, emptyList()).candidate
            assertFailsWith<IllegalArgumentException>(entry.name) { extractUpdatePayload(candidate, archive, tmp.resolve("updates")) }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `stages valid tar payload and preserves executables`(@TempDir tmp: Path) {
        val archive = tmp.resolve("update.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(Files.newOutputStream(archive))).use { tar ->
            val data = "launcher".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("Daemonitor/bin/Daemonitor").apply { size = data.size.toLong(); mode = 493 })
            tar.write(data)
            tar.closeArchiveEntry()
        }
        val candidate = stagedUpdate(DesktopPlatform.LINUX, tmp, tmp, archive, emptyList()).candidate
        val payload = extractUpdatePayload(candidate, archive, tmp.resolve("updates"))
        assertEquals("launcher", Files.readString(payload.resolve("bin/Daemonitor")))
        assertTrue(Files.isExecutable(payload.resolve("bin/Daemonitor")))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `refuses staging symlinks without deleting outside data`(@TempDir tmp: Path) {
        val outside = Files.createDirectories(tmp.resolve("outside"))
        val sentinel = Files.writeString(outside.resolve("keep"), "keep")
        val updates = Files.createDirectories(tmp.resolve("updates"))
        Files.createSymbolicLink(updates.resolve("staged"), outside)
        val archive = tmp.resolve("update.zip")
        val candidate = stagedUpdate(DesktopPlatform.LINUX, tmp, tmp, archive, emptyList()).candidate
        assertFailsWith<IllegalArgumentException> { extractUpdatePayload(candidate, archive, updates) }
        assertEquals("keep", Files.readString(sentinel))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `cleans old staging without following nested symlinks`(@TempDir tmp: Path) {
        val outside = Files.createDirectories(tmp.resolve("outside"))
        val sentinel = Files.writeString(outside.resolve("keep"), "keep")
        val oldStage = Files.createDirectories(tmp.resolve("updates/staged/1.0.8"))
        Files.createSymbolicLink(oldStage.resolve("link"), outside)
        val archive = tmp.resolve("update.zip").also { writeMacAppZip(it) }
        val candidate = stagedUpdate(DesktopPlatform.MACOS, tmp, tmp, archive, emptyList()).candidate
        extractUpdatePayload(candidate, archive, tmp.resolve("updates"))
        assertEquals("keep", Files.readString(sentinel))
        assertTrue(!Files.exists(oldStage.resolve("link")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `windows helper applies paths with metacharacters`(@TempDir tmp: Path) {
        val app = tmp.resolve("app & %PATH% ^ ! ' [1]").also { Files.createDirectories(it) }
        Files.writeString(app.resolve("version"), "previous")
        val payload = tmp.resolve("staged/app & %PATH% ^ ! ' [1]").also { Files.createDirectories(it) }
        Files.writeString(payload.resolve("version"), "updated")
        val artifact = Files.writeString(tmp.resolve("update.zip"), "zip")
        DesktopUpdateApplier(
            processId = unusedProcessId(),
            processStarter = { command, _ ->
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                assertTrue(process.waitFor(20, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            },
        ).applyAfterExit(stagedUpdate(DesktopPlatform.WINDOWS, app, payload, artifact, listOf("cmd.exe", "/c", "exit 0")))
        assertEquals("updated", Files.readString(app.resolve("version")))
        assertTrue(!Files.exists(artifact))
        assertTrue(!Files.exists(tmp.resolve("apply-update.ps1")))
        assertTrue(Files.list(tmp).use { files -> files.noneMatch { it.fileName.toString().startsWith("rollback-") } })
    }

    @Test
    fun `rejects oversized downloads before networking`(@TempDir tmp: Path) = runTest {
        val candidate = stagedUpdate(DesktopPlatform.MACOS, tmp, tmp, tmp.resolve("update.zip"), emptyList()).candidate
            .copy(sizeBytes = 1024L * 1024 * 1024 + 1, sha256 = "a".repeat(64))
        assertFailsWith<IllegalArgumentException> {
            DesktopUpdateInstaller(updateDirectory = tmp, installation = macInstall()).prepare(candidate) {}
        }
    }

    private fun unusedProcessId(): Long =
        generateSequence(Int.MAX_VALUE.toLong()) { it - 1L }
            .first { ProcessHandle.of(it).isEmpty }

    private fun stagedUpdate(
        platform: DesktopPlatform,
        app: Path,
        staged: Path,
        artifact: Path,
        relaunchCommand: List<String>,
    ): StagedUpdate = StagedUpdate(
        candidate = UpdateCandidate(
            version = "1.0.8",
            releaseUrl = "https://github.com/cdsap/daemonitor/releases/tag/v1.0.8",
            assetName = artifact.fileName.toString(),
            downloadUrl = "https://github.com/cdsap/daemonitor/releases/download/v1.0.8/${artifact.fileName}",
            platform = platform,
            architecture = CpuArchitecture.UNKNOWN,
            role = UpdateArtifactRole.UpdatePackage,
            installMode = UpdateInstallMode.Automatic,
        ),
        artifactPath = artifact,
        payloadPath = staged,
        installation = InstallationInfo(
            platform = platform,
            architecture = CpuArchitecture.UNKNOWN,
            kind = if (platform == DesktopPlatform.WINDOWS) {
                InstallationKind.WINDOWS_APP_DIR
            } else {
                InstallationKind.LINUX_STANDALONE
            },
            installRoot = app,
            relaunchCommand = relaunchCommand,
        ),
    )

    private fun macInstall(): InstallationInfo = InstallationInfo(
        platform = DesktopPlatform.MACOS,
        architecture = CpuArchitecture.ARM64,
        kind = InstallationKind.MACOS_APP_BUNDLE,
        installRoot = Path.of("/Applications/Daemonitor.app"),
        relaunchCommand = listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"),
    )

    private fun writeMacAppZip(target: Path) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("Daemonitor.app/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Daemonitor.app/Contents/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Daemonitor.app/Contents/Info.plist"))
            zip.write("plist".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Daemonitor.app/Contents/MacOS/Daemonitor"))
            zip.write("launcher".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Daemonitor.app/Contents/runtime/Contents/Home/bin/java"))
            zip.write("java".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Daemonitor.app/Contents/runtime/Contents/Home/lib/jspawnhelper"))
            zip.write("jspawnhelper".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Daemonitor.app/Contents/app/resources/daemonitor-cored"))
            zip.write("cored".toByteArray())
            zip.closeEntry()
        }
    }
}
