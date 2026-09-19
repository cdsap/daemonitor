package io.github.cdsap.daemonitor

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Job-control shells leave SIGINT ignored across `exec`. POSIX shells cannot reset that
 * disposition with `trap`, and the JVM skips its SIGINT shutdown handler when the signal was
 * ignored at startup — so background `kill -INT` would otherwise leave the process alive.
 *
 * Clear the inherited ignore and install a SIGINT handler that requests an orderly [System.exit],
 * which runs shutdown hooks.
 */
object PosixStopSignals {
    private val installed = AtomicBoolean(false)
    private val retainedHandlers = mutableListOf<Any>()

    fun ensureDeliverable() {
        if (isWindows() || !installed.compareAndSet(false, true)) return
        if (installJnaHandler() || installSunMiscHandler()) return
        installed.set(false)
    }

    /** Visible for tests — true after a successful install in this JVM. */
    internal fun isInstalled(): Boolean = installed.get()

    private fun installJnaHandler(): Boolean =
        runCatching {
            val libc = loadLibc()
            // Clear inherited SIG_IGN so a handler can run (HotSpot leaves INT ignored otherwise).
            libc.signal(SIGINT, SIG_DFL)
            val handler = SignalHandler { signo -> requestExit(128 + signo) }
            retainedHandlers += handler
            libc.signal(SIGINT, handler)
            true
        }.getOrDefault(false)

    private fun installSunMiscHandler(): Boolean =
        runCatching {
            val signalClass = Class.forName("sun.misc.Signal")
            val handlerClass = Class.forName("sun.misc.SignalHandler")
            val signal = signalClass.getConstructor(String::class.java).newInstance("INT")
            val handler =
                java.lang.reflect.Proxy.newProxyInstance(
                    handlerClass.classLoader,
                    arrayOf(handlerClass),
                ) { _, method, _ ->
                    if (method.name == "handle") requestExit(128 + SIGINT)
                    null
                }
            retainedHandlers += handler
            signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler)
            true
        }.getOrDefault(false)

    private fun requestExit(status: Int) {
        // Non-daemon so the thread is not discarded before System.exit runs.
        Thread({ System.exit(status) }, "daemonitor-signal-exit").apply {
            isDaemon = false
            start()
        }
    }

    private fun loadLibc(): CLibrary {
        val names = listOf("c", "libc.so.6", "libc.so")
        var last: Throwable? = null
        for (name in names) {
            try {
                return Native.load(name, CLibrary::class.java)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Unable to load libc", last)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    private fun interface SignalHandler : Callback {
        fun invoke(sig: Int)
    }

    private interface CLibrary : Library {
        fun signal(sig: Int, handler: Pointer?): Pointer?
        fun signal(sig: Int, handler: Callback): Pointer?
    }

    private val SIG_DFL: Pointer? = Pointer.NULL
    private const val SIGINT = 2
}
