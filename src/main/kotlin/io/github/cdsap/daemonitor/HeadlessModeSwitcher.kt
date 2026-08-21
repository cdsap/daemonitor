package io.github.cdsap.daemonitor

internal object HeadlessModeSwitcher {
    private val options = AppLaunchCommand.Options(extraArgs = listOf("--headless"))

    fun launch(): Process = AppLaunchCommand.start(currentProcessCommand())

    internal fun commandForCurrentProcess(
        executable: String?,
        javaHome: String,
        classpath: String,
        osName: String = System.getProperty("os.name"),
    ): List<String> =
        AppLaunchCommand.buildCommand(
            executable = executable,
            javaHome = javaHome,
            classpath = classpath,
            osName = osName,
            options = options,
        )

    private fun currentProcessCommand(): List<String> {
        val process = AppLaunchCommand.currentProcess()
        return commandForCurrentProcess(
            executable = process.executable,
            javaHome = process.javaHome,
            classpath = process.classpath,
        )
    }
}
