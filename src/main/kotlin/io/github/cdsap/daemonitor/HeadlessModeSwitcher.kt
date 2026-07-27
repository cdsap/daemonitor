package io.github.cdsap.daemonitor

import java.io.File

internal object HeadlessModeSwitcher {
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
    ): List<String> {
        if (executable != null && !executable.isJavaExecutable()) {
            return listOf(executable, "--headless")
        }

        val javaExecutable = File(File(javaHome, "bin"), javaExecutableName()).absolutePath
        return listOf(
            javaExecutable,
            "-cp",
            classpath,
            "io.github.cdsap.daemonitor.Daemonitor",
            "--headless",
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
        val name = File(this).name.lowercase()
        return name == "java" || name == "java.exe"
    }

    private fun javaExecutableName(): String =
        if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java"
}
