package io.github.cdsap.daemonitor

internal object HeadlessModeSwitcher {
    fun launch(): Process = AppLaunchCommand.start(currentProcessCommand())

    internal fun commandForCurrentProcess(
        executable: String?,
        javaHome: String,
        classpath: String,
        osName: String = System.getProperty("os.name"),
    ): List<String> {
        if (executable != null && !AppLaunchCommand.isJavaExecutable(executable)) {
            return listOf(executable, "--headless")
        }

        return AppLaunchCommand.javaClasspathLaunch(
            javaHome = javaHome,
            classpath = classpath,
            osName = osName,
            extraArgs = listOf("--headless"),
        )
    }

    private fun currentProcessCommand(): List<String> {
        val process = AppLaunchCommand.currentProcess()
        return commandForCurrentProcess(
            executable = process.executable,
            javaHome = process.javaHome,
            classpath = process.classpath,
        )
    }
}
