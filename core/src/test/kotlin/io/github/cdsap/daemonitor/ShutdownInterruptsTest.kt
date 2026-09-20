package io.github.cdsap.daemonitor

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShutdownInterruptsTest {
    @Test
    fun `interrupt-driven failures are treated as shutdown`() {
        assertTrue(ShutdownInterrupts.matches(ClosedByInterruptException()))
        assertTrue(ShutdownInterrupts.matches(InterruptedException("stop")))
        assertTrue(ShutdownInterrupts.matches(InterruptedIOException("stop")))
        assertTrue(ShutdownInterrupts.matches(CancellationException("cancelled")))
        assertTrue(ShutdownInterrupts.matches(IOException("wrap", ClosedByInterruptException())))
    }

    @Test
    fun `ordinary poll failures are not treated as shutdown`() {
        assertFalse(ShutdownInterrupts.matches(IOException("permission denied")))
        assertFalse(ShutdownInterrupts.matches(IllegalStateException("boom")))
    }
}
