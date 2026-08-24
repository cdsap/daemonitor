package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.MonitoringConfig
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the desktop monitoring poll loop lifecycle.
 *
 * Callers supply [tick] so presentation adapters can project poll results into UI state without
 * this service knowing about ViewModels.
 */
class MonitoringService(
    private val pollAction: suspend () -> WatcherRuntime.PollResult,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollInterval: Duration = MonitoringConfig.DEFAULT.pollInterval,
) {
    private var serviceJob: Job? = null

    @Volatile
    var boundScope: CoroutineScope? = null
        private set

    fun start(parent: CoroutineScope, tick: suspend () -> Unit) {
        serviceJob?.cancel()
        val job = SupervisorJob(parent.coroutineContext[Job])
        serviceJob = job
        val scope = CoroutineScope(parent.coroutineContext + job)
        boundScope = scope
        scope.launch(ioDispatcher) {
            while (isActive) {
                tick()
                delay(pollInterval)
            }
        }
    }

    suspend fun stop() {
        val job = serviceJob
        serviceJob = null
        boundScope = null
        job?.cancelAndJoin()
    }

    suspend fun poll(): WatcherRuntime.PollResult = pollAction()

    /**
     * One retryable poll that swallows non-cancellation failures via [onFailure].
     * Defaults keep the loop alive when no handlers are provided.
     */
    suspend fun pollSafely(
        onResult: suspend (WatcherRuntime.PollResult) -> Unit = {},
        onFailure: suspend (Exception) -> Unit = {},
    ) {
        try {
            onResult(poll())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
        }
    }

    fun launchIo(block: suspend CoroutineScope.() -> Unit): Job? =
        boundScope?.launch(ioDispatcher, block = block)
}
