package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.ui.settings.UpdateUiState
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import io.github.cdsap.daemonitor.update.UpdateInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WatcherServiceTest {
    @Test
    fun `failed poll records sanitized error and failure timestamp`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        val service = service(database, tmp, uiDispatcher = uiDispatcher, clock = { 100 }) {
            error("secret command line and log content")
        }
        try {
            service.pollSafely()

            val error = service.liveViewModel.state.value.pollError
            assertEquals(100, error?.failedAtMs)
            assertEquals("IllegalStateException", error?.errorType)
            assertFalse(error.toString().contains("secret"))
        } finally {
            service.stop()
            database.close()
        }
    }

    @Test
    fun `repeated failure replaces the latest failure timestamp`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        var now = 100L
        var firstFailure = true
        val service = service(database, tmp, uiDispatcher = uiDispatcher, clock = { now }) {
            if (firstFailure) throw IllegalArgumentException("first failure")
            error("second failure")
        }
        try {
            service.pollSafely()
            firstFailure = false
            now = 200
            service.pollSafely()

            val error = service.liveViewModel.state.value.pollError
            assertEquals(200, error?.failedAtMs)
            assertEquals("IllegalStateException", error?.errorType)
        } finally {
            service.stop()
            database.close()
        }
    }

    @Test
    fun `successful retry clears the previous failure`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        var fail = true
        val service = service(database, tmp, uiDispatcher = uiDispatcher) {
            if (fail) error("failure")
            WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = false)
        }
        try {
            service.pollSafely()
            fail = false
            service.pollSafely()

            assertNull(service.liveViewModel.state.value.pollError)
        } finally {
            service.stop()
            database.close()
        }
    }

    @Test
    fun `starting service checks for updates`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        var updateChecks = 0
        val service = service(
            database = database,
            tmp = tmp,
            uiDispatcher = uiDispatcher,
            updateChecker = {
                updateChecks += 1
                UpdateCheckResult.UpToDate("1.0.3")
            },
        ) {
            WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = false)
        }
        try {
            service.start(backgroundScope)
            advanceUntilIdle()

            assertEquals(1, updateChecks)
            assertEquals(UpdateUiState.UpToDate("1.0.3"), service.settingsViewModel.state.value.updateState)
        } finally {
            service.stop()
            database.close()
        }
    }

    @Test
    fun `stop joins background work before database close`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        val pollEntered = CompletableDeferred<Unit>()
        val releasePoll = CompletableDeferred<Unit>()
        var pollStillRunningAfterStop = false
        val service = service(database, tmp, uiDispatcher = uiDispatcher) {
            pollEntered.complete(Unit)
            releasePoll.await()
            pollStillRunningAfterStop = true
            WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = false)
        }
        try {
            service.start(backgroundScope)
            withTimeout(5_000) { pollEntered.await() }

            withTimeout(5_000) { service.stop() }
            // Closing must be safe only after stop has joined in-flight IO.
            database.close()

            assertFalse(pollStillRunningAfterStop)
        } finally {
            releasePoll.cancel()
            runCatching { service.stop() }
            runCatching { database.close() }
        }
    }

    private fun service(
        database: WatcherDatabase,
        tmp: Path,
        uiDispatcher: CoroutineDispatcher,
        clock: () -> Long = { 0 },
        updateChecker: suspend () -> UpdateCheckResult = { UpdateCheckResult.UpToDate("1.0.3") },
        pollAction: suspend () -> WatcherRuntime.PollResult,
    ) = WatcherService(
        runtime = WatcherRuntime.create(database),
        database = database,
        settingsStore = SettingsStore(tmp.resolve("settings.properties")),
        clock = clock,
        pollAction = pollAction,
        updateChecker = updateChecker,
        updateInstaller = UpdateInstaller { _, _ -> null },
        updateApplier = UpdateApplier { },
        mcpServerFactory = {
            DaemonitorMcpServer(DefaultDaemonitorQueryService(database, ProcessSource { emptyList() }))
        },
        uiDispatcher = uiDispatcher,
        ioDispatcher = uiDispatcher,
    )
}
