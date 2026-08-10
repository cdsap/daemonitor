package io.github.cdsap.daemonitor.update

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isWritable

/**
 * Describes how the currently running Daemonitor binary is installed and whether the app can safely
 * replace that installation after a clean exit.
 */
data class InstallationInfo(
    val platform: DesktopPlatform,
    val architecture: CpuArchitecture,
    val kind: InstallationKind,
    val installRoot: Path?,
    val relaunchCommand: List<String>,
) {
    val supportsAutomaticUpdate: Boolean
        get() = when (kind) {
            InstallationKind.MACOS_APP_BUNDLE,
            InstallationKind.WINDOWS_APP_DIR,
            InstallationKind.LINUX_STANDALONE -> installRoot != null && relaunchCommand.isNotEmpty()
            InstallationKind.LINUX_PACKAGE_MANAGED,
            InstallationKind.DEVELOPMENT,
            InstallationKind.UNSUPPORTED -> false
        }

    val manualUpdateReason: String?
        get() = when (kind) {
            InstallationKind.LINUX_PACKAGE_MANAGED ->
                "This Linux installation is managed by the system package manager. Download the newer package and install it with your package manager."
            InstallationKind.DEVELOPMENT ->
                "This development launch cannot replace an installed application. Download the release artifact and install it manually."
            InstallationKind.UNSUPPORTED ->
                "Automatic updates are not supported for this installation. Download the release artifact and install it manually."
            else -> if (supportsAutomaticUpdate) null else
                "Automatic updates are not available for this installation. Download the release artifact and install it manually."
        }
}

enum class InstallationKind {
    MACOS_APP_BUNDLE,
    WINDOWS_APP_DIR,
    LINUX_STANDALONE,
    LINUX_PACKAGE_MANAGED,
    DEVELOPMENT,
    UNSUPPORTED,
}

object InstallationLocator {
    fun current(
        platform: DesktopPlatform = DesktopPlatform.current(),
        architecture: CpuArchitecture = CpuArchitecture.current(),
        executable: String? = ProcessHandle.current().info().command().orElse(null),
        javaHome: String = System.getProperty("java.home"),
        classpath: String = System.getProperty("java.class.path"),
        packageManagedProbe: (Path) -> Boolean = ::isLinuxPackageManaged,
    ): InstallationInfo {
        val command = executable?.takeIf { it.isNotBlank() }
        if (command != null && !command.isJavaExecutable()) {
            return when (platform) {
                DesktopPlatform.MACOS -> macInstallation(command, platform, architecture)
                DesktopPlatform.WINDOWS -> windowsInstallation(command, platform, architecture)
                DesktopPlatform.LINUX -> linuxInstallation(command, platform, architecture, packageManagedProbe)
                DesktopPlatform.UNKNOWN -> unsupported(platform, architecture)
            }
        }

        // Source / IDE launches use the system java launcher and cannot safely replace an install.
        return InstallationInfo(
            platform = platform,
            architecture = architecture,
            kind = InstallationKind.DEVELOPMENT,
            installRoot = null,
            relaunchCommand = listOf(
                javaExecutablePath(javaHome, platform),
                "-cp",
                classpath,
                "io.github.cdsap.daemonitor.Daemonitor",
            ),
        )
    }

    private fun macInstallation(
        executable: String,
        platform: DesktopPlatform,
        architecture: CpuArchitecture,
    ): InstallationInfo {
        val appPath = executable.macAppBundlePath()?.let(Path::of)
        if (appPath != null) {
            return InstallationInfo(
                platform = platform,
                architecture = architecture,
                kind = InstallationKind.MACOS_APP_BUNDLE,
                installRoot = appPath,
                relaunchCommand = listOf("/usr/bin/open", "-n", appPath.toString()),
            )
        }
        return unsupported(platform, architecture)
    }

    private fun windowsInstallation(
        executable: String,
        platform: DesktopPlatform,
        architecture: CpuArchitecture,
    ): InstallationInfo {
        val exePath = Path.of(executable)
        val installRoot = exePath.parent
        if (installRoot != null) {
            return InstallationInfo(
                platform = platform,
                architecture = architecture,
                kind = InstallationKind.WINDOWS_APP_DIR,
                installRoot = installRoot,
                relaunchCommand = listOf(exePath.toString()),
            )
        }
        return unsupported(platform, architecture)
    }

    private fun linuxInstallation(
        executable: String,
        platform: DesktopPlatform,
        architecture: CpuArchitecture,
        packageManagedProbe: (Path) -> Boolean,
    ): InstallationInfo {
        val exePath = Path.of(executable).toAbsolutePath().normalize()
        val installRoot = resolveLinuxInstallRoot(exePath) ?: return unsupported(platform, architecture)
        if (packageManagedProbe(exePath) || packageManagedProbe(installRoot)) {
            return InstallationInfo(
                platform = platform,
                architecture = architecture,
                kind = InstallationKind.LINUX_PACKAGE_MANAGED,
                installRoot = installRoot,
                relaunchCommand = listOf(exePath.toString()),
            )
        }
        val writable = installRoot.isWritable() || installRoot.parent?.isWritable() == true
        if (!writable) {
            return InstallationInfo(
                platform = platform,
                architecture = architecture,
                kind = InstallationKind.UNSUPPORTED,
                installRoot = installRoot,
                relaunchCommand = listOf(exePath.toString()),
            )
        }
        return InstallationInfo(
            platform = platform,
            architecture = architecture,
            kind = InstallationKind.LINUX_STANDALONE,
            installRoot = installRoot,
            relaunchCommand = listOf(exePath.toString()),
        )
    }

    private fun resolveLinuxInstallRoot(executable: Path): Path? {
        val parent = executable.parent ?: return null
        // jpackage layout: <root>/bin/Daemonitor
        if (parent.fileName?.toString() == "bin") {
            val root = parent.parent
            if (root != null && root.resolve("lib").isDirectory()) return root
        }
        return parent
    }

    private fun unsupported(
        platform: DesktopPlatform,
        architecture: CpuArchitecture,
    ): InstallationInfo = InstallationInfo(
        platform = platform,
        architecture = architecture,
        kind = InstallationKind.UNSUPPORTED,
        installRoot = null,
        relaunchCommand = emptyList(),
    )

    private fun isLinuxPackageManaged(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize().toString()
        if (normalized.startsWith("/usr/") || normalized == "/usr") return true
        return runCatching {
            val process = ProcessBuilder("dpkg", "-S", normalized)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0 && output.contains("daemonitor", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun String.isJavaExecutable(): Boolean {
        val name = substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name == "java" || name == "java.exe"
    }

    private fun String.macAppBundlePath(): String? {
        val marker = ".app/Contents/MacOS/"
        val index = indexOf(marker)
        if (index < 0) return null
        return substring(0, index + ".app".length)
    }

    private fun javaExecutablePath(javaHome: String, platform: DesktopPlatform): String {
        val name = if (platform == DesktopPlatform.WINDOWS) "java.exe" else "java"
        return if (platform == DesktopPlatform.WINDOWS) {
            "${javaHome.trimEnd('\\')}\\bin\\$name"
        } else {
            "${javaHome.trimEnd('/')}/bin/$name"
        }
    }
}
