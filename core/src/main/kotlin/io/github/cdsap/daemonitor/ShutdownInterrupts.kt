package io.github.cdsap.daemonitor

import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

/** Interrupt / cancellation failures that mean "stop monitoring", not a user-visible poll error. */
object ShutdownInterrupts {
    fun matches(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            when (current) {
                is InterruptedException,
                is ClosedByInterruptException,
                is InterruptedIOException,
                is CancellationException -> return true
            }
            current = current.cause
        }
        return false
    }
}
