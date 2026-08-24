package io.github.cdsap.daemonitor

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringServiceTest {
    @Test
    fun `poll returns the poll action result`() = runTest {
        val expected = WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = true)
        val service = MonitoringService(
            pollAction = { expected },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(expected, service.poll())
    }

    @Test
    fun `pollSafely reports failures without throwing`() = runTest {
        val service = MonitoringService(
            pollAction = { error("secret") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        var failureType: String? = null
        service.pollSafely(
            onFailure = { failureType = it::class.simpleName },
        )
        assertEquals("IllegalStateException", failureType)
    }

    @Test
    fun `start loops until stop joins in-flight work`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stillRunning = false
        val service = MonitoringService(
            pollAction = {
                entered.complete(Unit)
                release.await()
                stillRunning = true
                WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = false)
            },
            ioDispatcher = dispatcher,
            pollInterval = 1.milliseconds,
        )
        try {
            service.start(backgroundScope) {
                service.pollSafely()
            }
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { service.stop() }
            assertFalse(stillRunning)
        } finally {
            release.cancel()
            runCatching { service.stop() }
        }
    }

    @Test
    fun `successful pollSafely invokes onResult`() = runTest {
        val expected = WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = false)
        val service = MonitoringService(pollAction = { expected })
        var seen: WatcherRuntime.PollResult? = null
        service.pollSafely(onResult = { seen = it })
        assertEquals(expected, seen)
        assertTrue(seen != null)
    }
}
