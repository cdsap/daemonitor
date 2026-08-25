package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.store.WatcherDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatcherRuntimeTest {

    @Test
    fun `appended daemon log window is redacted and persisted with its build`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val versionDir = tmp.resolve("gradle/daemon/8.14.3").also { it.createDirectories() }
        val log = versionDir.resolve("daemon-75597.out.log")
        log.writeText("outside before window -Ptoken=before-secret\n")

        WatcherDatabase.open(tmp.resolve("watcher.db")).use { database ->
            val runtime = WatcherRuntime(
                collector = ProcessCollector(),
                logWatcher = DaemonLogWatcher(gradleUserHome = tmp.resolve("gradle")),
                aggregator = BuildAggregator(sampleProvider = database::samples),
                builds = database,
                processSamples = database,
            )

            runtime.pollOnce() // Establish the incremental-read offset.
            Files.writeString(
                log,
                buildString {
                    appendLine("2026-06-24T10:00:00.000-0700 [INFO] [daemon] Marking the daemon as busy, address: []")
                    appendLine("2026-06-24T10:00:00.010-0700 [INFO] [daemon] Daemon is about to start building Build{id=build-35, currentDir=/project}")
                    appendLine("executing with -Ptoken=window-secret")
                    appendLine("BUILD SUCCESSFUL in 1s")
                    appendLine("2026-06-24T10:00:01.000-0700 [INFO] [daemon] Marking the daemon as idle, address: []")
                    appendLine("outside after window")
                },
                StandardOpenOption.APPEND,
            )

            assertTrue(runtime.pollOnce().buildsChanged)

            val build = database.recent().single()
            val snippet = assertNotNull(build.logSnippet)
            assertTrue(snippet.contains("-Ptoken=***"))
            assertFalse(snippet.contains("window-secret"))
            assertFalse(snippet.contains("outside before window"))
            assertFalse(snippet.contains("outside after window"))
            assertEquals(5, snippet.lines().size)
        }
    }

    @Test
    fun `inactive daemon log with outcome but no idle is persisted`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val versionDir = tmp.resolve("gradle/daemon/9.6.1").also { it.createDirectories() }
        val log = versionDir.resolve("daemon-75597.out.log")
        log.writeText(
            buildString {
                appendLine("2026-07-28T10:20:45.687-0700 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as busy, address: []")
                appendLine("2026-07-28T10:20:45.689-0700 [INFO] [org.gradle.launcher.daemon.server.exec.StartBuildOrRespondWithBusy] Daemon is about to start building Build{id=build-96, currentDir=/project}")
                appendLine("BUILD SUCCESSFUL in 2s")
            },
        )

        WatcherDatabase.open(tmp.resolve("watcher.db")).use { database ->
            val logWatcher = DaemonLogWatcher(gradleUserHome = tmp.resolve("gradle"))
            val runtime = WatcherRuntime(
                collector = ProcessCollector(),
                logWatcher = logWatcher,
                aggregator = BuildAggregator(sampleProvider = database::samples),
                builds = database,
                processSamples = database,
            )

            val changed = runtime.processForBuilds(logWatcher.discover(), activeDaemonPids = emptySet())

            assertTrue(changed)
            val build = database.recent().single()
            assertEquals("build-96", build.buildId)
            assertEquals(FinalStatus.SUCCESS, build.finalStatus)
        }
    }
}
