package io.github.cdsap.daemonitor

import java.io.File

internal object DesktopModeSwitcher {
    fun launch(): Process {
        val command = currentProcessCommand()
        val builder = ProcessBuilder(command)
            .directory(File(System.getProperty("user.dir")))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        return builder.start()
    }

    internal fun commandForCurrentProcess(
        executable: String?,
        javaHome: String,
        classpath: String,
        osName: String = System.getProperty("os.name"),
    ): List<String> {
        if (executable != null && !executable.isJavaExecutable()) {
            if (osName.isMacOs()) {
                executable.macAppBundlePath()?.let { return listOf("/usr/bin/open", "-n", it) }
            }
            return listOf(executable)
        }

        return listOf(
            javaExecutablePath(javaHome, javaExecutableName(osName)),
            "-cp",
            classpath,
            "io.github.cdsap.daemonitor.Daemonitor",
        )
    }

    private fun currentProcessCommand(): List<String> {
        val info = ProcessHandle.current().info()
        return commandForCurrentProcess(
            executable = info.command().orElse(null),
            javaHome = System.getProperty("java.home"),
            classpath = System.getProperty("java.class.path"),
        )
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

    private fun String.isMacOs(): Boolean = lowercase().contains("mac")

    private fun javaExecutableName(osName: String): String =
        if (osName.lowercase().contains("windows")) "java.exe" else "java"

    private fun javaExecutablePath(javaHome: String, executableName: String): String =
        if (javaHome.contains('\\')) {
            "${javaHome.trimEnd('\\')}\\bin\\$executableName"
        } else {
            "${javaHome.trimEnd('/')}/bin/$executableName"
        }
}
