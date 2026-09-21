package io.github.cdsap.daemonitor.update

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `windows apply helper removes backup before relaunch`(@TempDir tmp: Path) {
        val app = tmp.resolve("Daemonitor.exe").also { Files.writeString(it, "previous") }
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
            relaunchCommand = listOf("Daemonitor.exe"),
        )
        val started = mutableListOf<List<String>>()

        DesktopUpdateApplier(
            processId = 4242,
            processStarter = { command, _ -> started += command },
        ).applyAfterExit(stagedUpdate)

        val script = Files.readString(tmp.resolve("apply-update.cmd"))
        assertTrue(script.contains("daemonitor-pre-update-%RANDOM%%RANDOM%"))
        assertTrue(script.contains("if exist \"%APP_PATH%.pre-update\" rmdir /s /q \"%APP_PATH%.pre-update\""))
        assertTrue(script.indexOf("rmdir /s /q \"%BACKUP%\"") < script.indexOf("start \"\""))
        assertEquals(listOf(listOf("cmd.exe", "/c", tmp.resolve("apply-update.cmd").toString())), started)
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
        }
    }
}
