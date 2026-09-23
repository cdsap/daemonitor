package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.coreipc.GoCoreWiring
import io.github.cdsap.daemonitor.coreipc.wireGoCore
import io.github.cdsap.daemonitor.platform.AppDirectories
import java.io.PrintStream
import java.nio.file.Path

/** Parsed launch options shared by desktop UI and `--headless` mode. */
data class DesktopLaunchOptions(
    val help: Boolean = false,
    val databasePath: Path? = null,
    val coreSocket: Path? = null,
) {
    fun resolveDatabasePath(): Path =
        databasePath ?: AppDirectories.system.databasePath

    fun goCoreWiring(): GoCoreWiring =
        wireGoCore(coreSocket, resolveDatabasePath())

    fun openContainer(): AppContainer {
        val databasePath = resolveDatabasePath()
        val wiring = goCoreWiring()
        return AppContainer(
            databasePath = databasePath,
            processSource = wiring.processSource,
            logSource = wiring.logSource,
            buildSource = wiring.buildSource,
            persistSamples = wiring.persistSamples,
        )
    }

    companion object {
        val USAGE = """
            Usage: daemonitor [options]
                   daemonitor --headless [options]

            Options:
              -h, --help       Show this help.
              --db PATH        Store data in this SQLite database.
              --core-socket PATH
                               Read live processes and daemon logs from daemonitor-cored.
                               When the core's db_path matches --db (default: app watcher.db),
                               the core owns sample/build writes.

            Default (no flags) uses the in-process JVM collector.
        """.trimIndent()

        fun parse(args: Array<String>, error: PrintStream = System.err): DesktopLaunchOptions? {
            var options = DesktopLaunchOptions()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                options = when (arg) {
                    "--help", "-h" -> options.copy(help = true)
                    "--db" -> {
                        val value = nextValue(args, ++index, arg, error) ?: return null
                        options.copy(databasePath = Path.of(value).toAbsolutePath().normalize())
                    }
                    "--core-socket" -> {
                        val value = nextValue(args, ++index, arg, error) ?: return null
                        options.copy(coreSocket = Path.of(value).toAbsolutePath().normalize())
                    }
                    else -> {
                        error.println("Unknown option: $arg")
                        error.println("Use --help for usage.")
                        return null
                    }
                }
                index++
            }
            return options
        }

        private fun nextValue(
            args: Array<String>,
            index: Int,
            option: String,
            error: PrintStream,
        ): String? {
            if (index >= args.size || args[index].startsWith("-")) {
                error.println("Missing value for $option")
                error.println("Use --help for usage.")
                return null
            }
            return args[index]
        }
    }
}
