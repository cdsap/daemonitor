package io.github.cdsap.daemonitor

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Job-control shells leave SIGINT ignored across `exec`. POSIX shells cannot reset that
 * disposition with `trap`, and the JVM skips its SIGINT shutdown handler when the signal was
 * ignored at startup — so background `kill -INT` would otherwise leave the process alive.
 *
 * Reinstall a SIGINT handler that requests an orderly [System.exit], which runs shutdown hooks.
 */
object PosixStopSignals {
    private val installed = AtomicBoolean(false)
    private val retainedHandlers = mutableListOf<Callback>()

    fun ensureDeliverable() {
        if (isWindows() || !installed.compareAndSet(false, true)) return
        runCatching {
            val libc = Native.load("c", CLibrary::class.java)
            val handler = SignalHandler { signo ->
                // Hand off to a dedicated thread; signal contexts must stay minimal.
                Thread({ System.exit(128 + signo) }, "daemonitor-signal-exit").apply {
                    isDaemon = true
                    start()
                }
            }
            retainedHandlers += handler
            libc.signal(SIGINT, handler)
        }.onFailure {
            installed.set(false)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    private fun interface SignalHandler : Callback {
        fun invoke(sig: Int)
    }

    private interface CLibrary : Library {
        fun signal(sig: Int, handler: Callback): com.sun.jna.Pointer?
    }

    private const val SIGINT = 2
}
