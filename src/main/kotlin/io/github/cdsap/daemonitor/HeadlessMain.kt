@file:JvmName("DaemonitorHeadless")

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

internal object HeadlessLauncher {
    fun run(
        args: Array<String>,
        output: PrintStream = System.out,
        error: PrintStream = System.err,
        input: InputStream = System.`in`,
    ): Int {
        if (args.any { it == "--help" || it == "-h" }) {
            output.println("Usage: daemonitor --headless")
            return 0
        }
        if (args.isNotEmpty()) {
            error.println("Unknown headless option: ${args.first()}")
            return 2
        }

        HeadlessMacMode.configure()
        return AppContainer().use { container ->
            runHeadless(container, output, error, input)
        }
    }

    private fun runHeadless(
        container: AppContainer,
        output: PrintStream,
        error: PrintStream,
        input: InputStream,
    ): Int {
        val runtime = container.runtime
        val retentionDays = container.settingsStore.load().retentionDays
        val terminal = HeadlessTerminalUi(
            output = output,
            input = input,
            clearScreen = isInteractiveTerminal(),
            colorEnabled = isInteractiveTerminal(),
        )
        val pollingThread = Thread.currentThread()
        val running = AtomicBoolean(true)
        val cleanupFinished = CountDownLatch(1)
        val shutdownHook = Thread({
            running.set(false)
            pollingThread.interrupt()
            cleanupFinished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }, "daemonitor-headless-shutdown")
        val tray = HeadlessTray.install(
            onOpen = {
                runCatching { DesktopModeSwitcher.launch() }
                    .onSuccess {
                        running.set(false)
                        pollingThread.interrupt()
                    }
                    .onFailure { error.println("Daemonitor desktop launch failed: ${it.message}") }
            },
            onQuit = {
                running.set(false)
                pollingThread.interrupt()
            },
            error = error,
        )

        return try {
            container.database.purgeOlderThan(System.currentTimeMillis(), retentionDays)
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            var lastResult = WatcherRuntime.PollResult(emptyList(), emptyList(), false)
            var pollError: String? = null
            runBlocking {
                while (currentCoroutineContext().isActive && running.get()) {
                    runCatching { runtime.pollOnce() }
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
            try {
                tray.close()
            } finally {
                cleanupFinished.countDown()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        }
    }

    private fun isInteractiveTerminal(): Boolean =
        System.console() != null ||
            (!System.getenv("TERM").isNullOrBlank() && System.getenv("TERM") != "dumb")

    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
}

fun main(args: Array<String>) {
    val exitCode = HeadlessLauncher.run(args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}
