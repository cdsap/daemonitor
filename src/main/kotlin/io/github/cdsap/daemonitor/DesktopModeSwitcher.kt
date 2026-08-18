package io.github.cdsap.daemonitor

internal object DesktopModeSwitcher {
    fun launch(): Process = AppLaunchCommand.start(currentProcessCommand())

    internal fun commandForCurrentProcess(
        executable: String?,
        javaHome: String,
        classpath: String,
        osName: String = System.getProperty("os.name"),
    ): List<String> {
        if (executable != null && !AppLaunchCommand.isJavaExecutable(executable)) {
            if (AppLaunchCommand.isMacOs(osName)) {
                AppLaunchCommand.macAppBundlePath(executable)?.let {
                    return listOf("/usr/bin/open", "-n", it)
                }
            }
            return listOf(executable)
        }

        return AppLaunchCommand.javaClasspathLaunch(javaHome, classpath, osName)
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
