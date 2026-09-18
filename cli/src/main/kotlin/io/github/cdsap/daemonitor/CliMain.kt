@file:JvmName("DaemonitorCli")

package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.config.RetentionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object CliLauncher {
    fun run(
        args: Array<String>,
        output: PrintStream = System.out,
        error: PrintStream = System.err,
        input: InputStream = System.`in`,
    ): Int {
        val options = parse(args, output, error) ?: return 2
        if (options.help) {
            output.println(USAGE)
            return 0
        }
        if (options.version) {
            output.println(BuildInfo.current.version)
            return 0
        }

        return CoreContainer(databasePath = options.databasePath ?: io.github.cdsap.daemonitor.platform.AppDirectories.system.databasePath).use { container ->
            runMonitor(container, options, output, error, input)
        }
    }

    private fun runMonitor(
        container: CoreContainer,
        options: CliOptions,
        output: PrintStream,
        error: PrintStream,
        input: InputStream,
    ): Int {
        val interactive = isInteractiveTerminal()
        val colorEnabled = options.colorEnabled ?: interactive
        val terminal = HeadlessTerminalUi(
            output = output,
            input = input,
            clearScreen = interactive && colorEnabled,
            colorEnabled = colorEnabled,
        )
        val running = AtomicBoolean(true)
        val pollingThread = Thread.currentThread()
        val cleanupFinished = CountDownLatch(1)
        val shutdownHook = Thread({
            running.set(false)
            pollingThread.interrupt()
            cleanupFinished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }, "daemonitor-cli-shutdown")

        return try {
            val retentionDays = options.retentionDays ?: container.settingsStore.load().retentionDays
            container.database.purgeOlderThan(System.currentTimeMillis(), retentionDays)
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            var lastResult = WatcherRuntime.PollResult(emptyList(), emptyList(), false)
            var pollError: String? = null
            runBlocking {
                while (currentCoroutineContext().isActive && running.get()) {
                    runCatching { container.runtime.pollOnce() }
                        .onSuccess {
                            lastResult = it
                            pollError = null
                        }
                        .onFailure {
                            pollError = it.message ?: it::class.simpleName ?: "unknown error"
                            error.println("Daemonitor poll failed: $pollError")
                        }
                    terminal.render(lastResult, System.currentTimeMillis(), pollError)
                    if (terminal.shouldQuit()) break
                    delay(options.pollInterval ?: MonitoringConfig.DEFAULT.pollInterval)
                }
            }
            0
        } catch (_: InterruptedException) {
            0
        } catch (_: CancellationException) {
            0
        } finally {
            cleanupFinished.countDown()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    private fun parse(args: Array<String>, output: PrintStream, error: PrintStream): CliOptions? {
        var options = CliOptions()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            options = when (arg) {
                "--help", "-h" -> options.copy(help = true)
                "--version", "-v" -> options.copy(version = true)
                "--plain", "--no-color" -> options.copy(colorEnabled = false)
                "--db" -> options.copy(
                    databasePath = nextValue(args, ++index, arg, error, output)
                        ?.let(Path::of)
                        ?.toAbsolutePath()
                        ?.normalize()
                        ?: return null,
                )
                "--poll-interval" -> options.copy(
                    pollInterval = nextValue(args, ++index, arg, error, output)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                        ?.seconds
                        ?: return invalidValue(arg, error, output),
                )
                "--retention" -> options.copy(
                    retentionDays = nextValue(args, ++index, arg, error, output)
                        ?.toLongOrNull()
                        ?.let { RetentionPolicy.DEFAULT.clamp(it) }
                        ?: return invalidValue(arg, error, output),
                )
                else -> return unknownOption(arg, error, output)
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
        output: PrintStream,
    ): String? {
        if (index >= args.size || args[index].startsWith("-")) {
            error.println("Missing value for $option")
            output.println("Use --help for usage.")
            return null
        }
        return args[index]
    }

    private fun invalidValue(option: String, error: PrintStream, output: PrintStream): Nothing? {
        error.println("Invalid value for $option")
        output.println("Use --help for usage.")
        return null
    }

    private fun unknownOption(option: String, error: PrintStream, output: PrintStream): Nothing? {
        error.println("Unknown option: $option")
        output.println("Use --help for usage.")
        return null
    }

    private fun isInteractiveTerminal(): Boolean =
        System.console() != null ||
            (!System.getenv("TERM").isNullOrBlank() && System.getenv("TERM") != "dumb")

    private data class CliOptions(
        val help: Boolean = false,
        val version: Boolean = false,
        val colorEnabled: Boolean? = null,
        val databasePath: Path? = null,
        val pollInterval: Duration? = null,
        val retentionDays: Long? = null,
    )

    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    private const val USAGE = """
Usage: daemonitor-cli [options]

Monitor Gradle-related processes in the terminal.

Options:
  -h, --help       Show this help.
  -v, --version    Show the Daemonitor version.
  --plain      Disable colors and terminal screen clearing.
      --no-color   Alias for --plain.
      --db PATH    Store data in this SQLite database.
      --poll-interval SECONDS
                   Poll at this interval (default: 2).
      --retention DAYS
                   Retain history for this many days (1-90).

Press q to quit.
"""
}

fun main(args: Array<String>) {
    val exitCode = CliLauncher.run(args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}
