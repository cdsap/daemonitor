@file:JvmName("DaemonitorCli")

package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.MonitoringConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        return CoreContainer().use { container ->
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
            val retentionDays = container.settingsStore.load().retentionDays
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
                    delay(MonitoringConfig.DEFAULT.pollInterval)
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
        for (arg in args) {
            options = when (arg) {
                "--help", "-h" -> options.copy(help = true)
                "--version", "-v" -> options.copy(version = true)
                "--plain", "--no-color" -> options.copy(colorEnabled = false)
                else -> {
                    error.println("Unknown option: $arg")
                    output.println("Use --help for usage.")
                    return null
                }
            }
        }
        return options
    }

    private fun isInteractiveTerminal(): Boolean =
        System.console() != null ||
            (!System.getenv("TERM").isNullOrBlank() && System.getenv("TERM") != "dumb")

    private data class CliOptions(
        val help: Boolean = false,
        val version: Boolean = false,
        val colorEnabled: Boolean? = null,
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

Press q to quit.
"""
}

fun main(args: Array<String>) {
    val exitCode = CliLauncher.run(args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}
