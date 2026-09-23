package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.coreipc.GoCorePreference
import io.github.cdsap.daemonitor.coreipc.GoCoreResolveResult
import io.github.cdsap.daemonitor.coreipc.resolveGoCore as resolveGoCoreSession
import io.github.cdsap.daemonitor.platform.AppDirectories
import java.io.PrintStream
import java.nio.file.Path

/** Parsed launch options shared by desktop UI and `--headless` mode. */
data class DesktopLaunchOptions(
    val help: Boolean = false,
    val databasePath: Path? = null,
    val coreSocket: Path? = null,
    val jvmCollector: Boolean = false,
) {
    fun resolveDatabasePath(): Path =
        databasePath ?: AppDirectories.system.databasePath

    fun goCorePreference(): GoCorePreference = when {
        jvmCollector -> GoCorePreference.JvmCollector
        coreSocket != null -> GoCorePreference.ExplicitSocket(coreSocket)
        else -> GoCorePreference.PreferGo
    }

    fun resolveCore(error: PrintStream = System.err): GoCoreResolveResult =
        resolveGoCoreSession(goCorePreference(), resolveDatabasePath(), error = error)

    fun openContainer(resolved: GoCoreResolveResult): AppContainer {
        val wiring = resolved.wiring
        return AppContainer(
            databasePath = resolveDatabasePath(),
            processSource = wiring.processSource,
            logSource = wiring.logSource,
            buildSource = wiring.buildSource,
            persistSamples = wiring.persistSamples,
        )
    }

    companion object {
        val USAGE = """
            Usage: daemonitor [options]
            Usage: daemonitor --headless [options]

            Options:
              -h, --help       Show this help.
              --db PATH        Store data in this SQLite database.
              --core-socket PATH
                               Use this daemonitor-cored socket (default: app-dir socket;
                               starts bundled cored when needed).
              --jvm-collector  Force the in-process JVM collector (skip Go core).

            By default, attaches to daemonitor-cored (auto-starts when packaged/on PATH).
            Live heap Attach/JMX is unavailable on the Go path.
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
                    "--jvm-collector" -> options.copy(jvmCollector = true)
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
