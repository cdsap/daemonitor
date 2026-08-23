@file:JvmName("DaemonitorHeadless")

package io.github.cdsap.daemonitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object HeadlessLauncher {
    fun run(
        args: Array<String>,
        output: PrintStream = System.out,
        error: PrintStream = System.err,
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
            runHeadless(container, output, error)
        }
    }

    private fun runHeadless(
        container: AppContainer,
        output: PrintStream,
        error: PrintStream,
    ): Int {
        val runtime = container.runtime
        val retentionDays = container.settingsStore.load().retentionDays
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
            output.println("Daemonitor headless collector started")
            runBlocking {
                while (currentCoroutineContext().isActive && running.get()) {
                    runCatching { runtime.pollOnce() }
                        .onFailure { error.println("Daemonitor poll failed: ${it.message}") }
                    delay(Defaults.POLL_INTERVAL)
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

    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
}

fun main(args: Array<String>) {
    val exitCode = HeadlessLauncher.run(args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}
