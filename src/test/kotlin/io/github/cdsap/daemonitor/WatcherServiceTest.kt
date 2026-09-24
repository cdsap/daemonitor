package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.DaemonLogLine
import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import io.github.cdsap.daemonitor.application.platform.UrlOpener
import io.github.cdsap.daemonitor.application.update.ApplyUpdate
import io.github.cdsap.daemonitor.application.update.CheckForUpdate
import io.github.cdsap.daemonitor.application.update.PrepareUpdate
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.application.update.UpdateSource
import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import io.github.cdsap.daemonitor.ui.live.LogTailState
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
import kotlin.test.assertTrue

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
    fun `select loads daemon log immediately without waiting for another poll`(@TempDir tmp: Path) = runTest {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        val process = GradleProcess(
            pid = 42,
            parentPid = 1,
            type = ProcessType.GRADLE_DAEMON,
            commandLine = "java GradleDaemon",
            workingDirectory = "/project",
            projectPath = "/project",
            cpuPercent = 1.0,
            rssMemoryMb = 512,
            maxHeapMb = 1024,
            minHeapMb = null,
            gc = "G1",
            startTimeMs = 1,
            status = "RUNNING",
        )
        val log = DaemonLog(pid = 42, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-42.out.log"))
        var tailCalls = 0
        val logSource = object : DaemonLogSource {
            override fun discover(): List<DaemonLog> = listOf(log)
            override fun readNewLines(log: DaemonLog): List<DaemonLogLine> = emptyList()
            override fun tailFor(log: DaemonLog): List<String> {
                tailCalls += 1
                return listOf("immediate-tail")
            }
        }
        val runtime = WatcherRuntime(
            processSource = ProcessSource { listOf(process) },
            logSource = logSource,
            aggregator = BuildAggregator(
                sampleProvider = database::samplesInWindow,
                ambientEnvNames = emptySet(),
                logSnippetLimit = with(MonitoringConfig.DEFAULT.logSnippetLimit) {
                    BuildAggregator.LogSnippetLimit(lines = lines, chars = chars)
                },
            ),
            builds = database,
            samples = database,
        )
        val service = WatcherService.forTests(
            runtime = runtime,
            database = database,
            settingsRepository = SettingsStore(tmp.resolve("settings.properties")),
            pollAction = { runtime.pollOnce() },
            mcpServerFactory = {
                DaemonitorMcpServer(DefaultDaemonitorQueryService(database, database, ProcessSource { emptyList() }))
            },
            uiDispatcher = uiDispatcher,
            ioDispatcher = uiDispatcher,
        )
        try {
            service.start(backgroundScope)
            advanceUntilIdle()
            assertEquals(listOf(42L), service.liveViewModel.state.value.processes.map { it.pid })
            assertEquals(LogTailState.NoSelection, service.liveViewModel.state.value.tailState)
            val tailCallsAfterPoll = tailCalls

            service.select(42)
            advanceUntilIdle()

            assertTrue(tailCalls > tailCallsAfterPoll)
            assertEquals(listOf("immediate-tail"), service.liveViewModel.state.value.tail)
            assertEquals(LogTailState.Available(listOf("immediate-tail")), service.liveViewModel.state.value.tailState)
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
            updateService = UpdateService(
                checkForUpdate = CheckForUpdate(
                    source = UpdateSource {
                        updateChecks += 1
                        UpdateCheckResult.UpToDate("1.0.3")
                    },
                    currentVersion = { "1.0.3" },
                ),
                prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
                applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
                urlOpener = UrlOpener {},
            ),
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
        updateService: UpdateService = UpdateService(
            checkForUpdate = CheckForUpdate(
                source = UpdateSource { UpdateCheckResult.UpToDate("1.0.3") },
                currentVersion = { "1.0.3" },
            ),
            prepareUpdate = PrepareUpdate(UpdateInstaller { _, _ -> null }),
            applyUpdate = ApplyUpdate(UpdateApplier {}, ProcessExiter {}),
            urlOpener = UrlOpener {},
        ),
        pollAction: suspend () -> WatcherRuntime.PollResult,
    ) = WatcherService.forTests(
        runtime = WatcherRuntime(
            processSource = ProcessCollector(),
            logSource = DaemonLogWatcher(),
            aggregator = BuildAggregator(
                sampleProvider = database::samplesInWindow,
                ambientEnvNames = System.getenv().keys.toSet(),
                logSnippetLimit = with(MonitoringConfig.DEFAULT.logSnippetLimit) {
                    BuildAggregator.LogSnippetLimit(lines = lines, chars = chars)
                },
            ),
            builds = database,
            samples = database,
        ),
        database = database,
        settingsRepository = SettingsStore(tmp.resolve("settings.properties")),
        clock = clock,
        pollAction = pollAction,
        updateService = updateService,
        mcpServerFactory = {
            DaemonitorMcpServer(DefaultDaemonitorQueryService(database, database, ProcessSource { emptyList() }))
        },
        uiDispatcher = uiDispatcher,
        ioDispatcher = uiDispatcher,
    )
}
