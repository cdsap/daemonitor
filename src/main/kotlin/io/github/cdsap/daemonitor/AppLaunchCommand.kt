package io.github.cdsap.daemonitor

import java.io.File

internal object AppLaunchCommand {
    private const val ENTRY_POINT = "io.github.cdsap.daemonitor.Daemonitor"

    fun start(command: List<String>): Process =
        ProcessBuilder(command)
            .directory(File(System.getProperty("user.dir")))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

    fun currentProcess(): CurrentProcess {
        val info = ProcessHandle.current().info()
        return CurrentProcess(
            executable = info.command().orElse(null),
            javaHome = System.getProperty("java.home"),
            classpath = System.getProperty("java.class.path"),
        )
    }

    fun isJavaExecutable(executable: String): Boolean {
        val name = executable.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name == "java" || name == "java.exe"
    }

    fun isMacOs(osName: String): Boolean = osName.lowercase().contains("mac")

    fun macAppBundlePath(executable: String): String? {
        val marker = ".app/Contents/MacOS/"
        val index = executable.indexOf(marker)
        if (index < 0) return null
        return executable.substring(0, index + ".app".length)
    }

    fun javaClasspathLaunch(
        javaHome: String,
        classpath: String,
        osName: String,
        extraArgs: List<String> = emptyList(),
    ): List<String> =
        listOf(
            javaExecutablePath(javaHome, javaExecutableName(osName)),
            "-cp",
            classpath,
            ENTRY_POINT,
        ) + extraArgs

    private fun javaExecutableName(osName: String): String =
        if (osName.lowercase().contains("windows")) "java.exe" else "java"

    private fun javaExecutablePath(javaHome: String, executableName: String): String =
        if (javaHome.contains('\\')) {
            "${javaHome.trimEnd('\\')}\\bin\\$executableName"
        } else {
            "${javaHome.trimEnd('/')}/bin/$executableName"
        }

    data class CurrentProcess(
        val executable: String?,
        val javaHome: String,
        val classpath: String,
    )
}
