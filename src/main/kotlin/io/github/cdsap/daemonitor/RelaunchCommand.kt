package io.github.cdsap.daemonitor

import java.io.File

internal object RelaunchCommand {
    private const val ENTRY_POINT = "io.github.cdsap.daemonitor.Daemonitor"

    data class Options(
        val extraArgs: List<String> = emptyList(),
        val reopenMacDesktopBundle: Boolean = false,
    )

    fun launch(options: Options): Process {
        val process = currentProcess()
        return start(
            buildCommand(
                executable = process.executable,
                javaHome = process.javaHome,
                classpath = process.classpath,
                osName = System.getProperty("os.name"),
                options = options,
            ),
        )
    }

    /** Opens a visible macOS Terminal session for terminal-oriented modes. */
    fun launchInTerminal(options: Options): Process {
        val process = currentProcess()
        val command = buildCommand(
            executable = process.executable,
            javaHome = process.javaHome,
            classpath = process.classpath,
            osName = System.getProperty("os.name"),
            options = options,
        )
        if (!isMacOs(System.getProperty("os.name"))) return start(command)

        val script = "tell application \"Terminal\" to do script \"${appleScriptString(shellCommand(command))}\""
        return ProcessBuilder("/usr/bin/osascript", "-e", script)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    }

    private fun start(command: List<String>): Process =
        ProcessBuilder(command)
            .directory(File(System.getProperty("user.dir")))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

    private fun currentProcess(): CurrentProcess {
        val info = ProcessHandle.current().info()
        return CurrentProcess(
            executable = info.command().orElse(null),
            javaHome = System.getProperty("java.home"),
            classpath = System.getProperty("java.class.path"),
        )
    }

    fun buildCommand(
        executable: String?,
        javaHome: String,
        classpath: String,
        osName: String,
        options: Options,
    ): List<String> {
        if (executable != null && !isJavaExecutable(executable)) {
            if (options.reopenMacDesktopBundle && isMacOs(osName)) {
                macAppBundlePath(executable)?.let {
                    return listOf("/usr/bin/open", "-n", it)
                }
            }
            return listOf(executable) + options.extraArgs
        }

        return listOf(
            javaExecutablePath(javaHome, osName),
            "-cp",
            classpath,
            ENTRY_POINT,
        ) + options.extraArgs
    }

    fun isJavaExecutable(executable: String): Boolean {
        val name = executable.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name == "java" || name == "java.exe"
    }

    fun macAppBundlePath(executable: String): String? {
        val marker = ".app/Contents/MacOS/"
        val index = executable.indexOf(marker)
        if (index < 0) return null
        return executable.substring(0, index + ".app".length)
    }

    fun javaExecutablePath(javaHome: String, osName: String): String {
        val executableName = javaExecutableName(osName)
        return if (javaHome.contains('\\')) {
            "${javaHome.trimEnd('\\')}\\bin\\$executableName"
        } else {
            "${javaHome.trimEnd('/')}/bin/$executableName"
        }
    }

    internal fun shellCommand(command: List<String>): String =
        command.joinToString(" ") { argument ->
            "'${argument.replace("'", "'\\''")}'"
        }

    private fun appleScriptString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun isMacOs(osName: String): Boolean = osName.lowercase().contains("mac")

    private fun javaExecutableName(osName: String): String =
        if (osName.lowercase().contains("windows")) "java.exe" else "java"

    private data class CurrentProcess(
        val executable: String?,
        val javaHome: String,
        val classpath: String,
    )
}
