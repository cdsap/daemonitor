package io.github.cdsap.daemonitor

internal object DesktopModeSwitcher {
    private val options = RelaunchCommand.Options(reopenMacDesktopBundle = true)

    fun launch(): Process = RelaunchCommand.launch(options)

    internal fun commandForCurrentProcess(
        executable: String?,
        javaHome: String,
        classpath: String,
        osName: String = System.getProperty("os.name"),
    ): List<String> =
        RelaunchCommand.buildCommand(
            executable = executable,
            javaHome = javaHome,
            classpath = classpath,
            osName = osName,
            options = options,
        )
}
