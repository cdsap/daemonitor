package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.ui.settings.UpdateUiState
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        try {
            val service = service(database, tmp, uiDispatcher = uiDispatcher, clock = { 100 }) {
                error("secret command line and log content")
            }

            service.pollSafely()

            val error = service.liveViewModel.state.value.pollError
            assertEquals(100, error?.failedAtMs)
            assertEquals("IllegalStateException", error?.errorType)
            assertFalse(error.toString().contains("secret"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `repeated failure replaces the latest failure timestamp`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        try {
            var now = 100L
            var firstFailure = true
            val service = service(database, tmp, uiDispatcher = uiDispatcher, clock = { now }) {
                if (firstFailure) throw IllegalArgumentException("first failure")
                error("second failure")
            }

            service.pollSafely()
            firstFailure = false
            now = 200
            service.pollSafely()

            val error = service.liveViewModel.state.value.pollError
            assertEquals(200, error?.failedAtMs)
            assertEquals("IllegalStateException", error?.errorType)
        } finally {
            database.close()
        }
    }

    @Test
    fun `successful retry clears the previous failure`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        try {
            var fail = true
            val service = service(database, tmp, uiDispatcher = uiDispatcher) {
                if (fail) error("failure")
                WatcherRuntime.PollResult(emptyList(), emptyList(), buildsChanged = false)
            }

            service.pollSafely()
            fail = false
            service.pollSafely()

            assertNull(service.liveViewModel.state.value.pollError)
        } finally {
            database.close()
        }
    }

    @Test
    fun `starting service checks for updates`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        try {
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

            service.start(backgroundScope)
            advanceUntilIdle()

            assertEquals(1, updateChecks)
            assertEquals(UpdateUiState.UpToDate("1.0.3"), service.settingsViewModel.state.value.updateState)
        } finally {
            database.close()
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
        uiDispatcher = uiDispatcher,
    )
}
