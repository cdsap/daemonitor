package io.github.cdsap.daemonitor

internal object HeadlessModeSwitcher {
    private val options = RelaunchCommand.Options(extraArgs = listOf("--headless"))

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
