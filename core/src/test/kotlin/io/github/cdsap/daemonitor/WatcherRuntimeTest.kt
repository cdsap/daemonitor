package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.collect.JvmHeapProbe
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.store.WatcherDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatcherRuntimeTest {

    private fun collectorWithoutHeapAttach() = ProcessCollector(
        heapProbe = JvmHeapProbe { _, sampledAtMs -> LiveJvmHeap.unavailable(sampledAtMs) },
    )

    @Test
    fun `appended daemon log window is redacted and persisted with its build`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val versionDir = tmp.resolve("gradle/daemon/8.14.3").also { it.createDirectories() }
        val log = versionDir.resolve("daemon-75597.out.log")
        log.writeText("outside before window -Ptoken=before-secret\n")
        val clockMs = OffsetDateTime.of(2026, 6, 24, 10, 0, 1, 0, ZoneOffset.ofHours(-7))
            .toInstant().toEpochMilli()

        WatcherDatabase.open(tmp.resolve("watcher.db")).use { database ->
            val runtime = WatcherRuntime(
                processSource = collectorWithoutHeapAttach(),
                logSource = DaemonLogWatcher(gradleUserHome = tmp.resolve("gradle")),
                aggregator = BuildAggregator(sampleProvider = database::samples),
                builds = database,
                samples = database,
                clock = { clockMs },
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
        val clockMs = OffsetDateTime.of(2026, 7, 28, 10, 20, 47, 0, ZoneOffset.ofHours(-7))
            .toInstant().toEpochMilli()

        WatcherDatabase.open(tmp.resolve("watcher.db")).use { database ->
            val logWatcher = DaemonLogWatcher(gradleUserHome = tmp.resolve("gradle"))
            val runtime = WatcherRuntime(
                processSource = collectorWithoutHeapAttach(),
                logSource = logWatcher,
                aggregator = BuildAggregator(sampleProvider = database::samples),
                builds = database,
                samples = database,
                clock = { clockMs },
            )

            val changed = runtime.processForBuilds(logWatcher.discover(), activeDaemonPids = emptySet())

            assertTrue(changed)
            val build = database.recent().single()
            assertEquals("build-96", build.buildId)
            assertEquals(FinalStatus.SUCCESS, build.finalStatus)
        }
    }

    @Test
    fun `purge plus log replay does not resurrect builds outside retention`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val versionDir = tmp.resolve("gradle/daemon/8.14.3").also { it.createDirectories() }
        val log = versionDir.resolve("daemon-9001.out.log")
        val day = RetentionPolicy.MILLIS_PER_DAY
        val now = 200L * day
        val agedStart = now - 10 * day
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            .withZone(ZoneOffset.ofHours(-7))
            .format(Instant.ofEpochMilli(agedStart))
        log.writeText(
            buildString {
                appendLine("$ts [INFO] [daemon] Marking the daemon as busy, address: []")
                appendLine("$ts [INFO] [daemon] Daemon is about to start building Build{id=aged-replay, currentDir=/old}")
                appendLine("BUILD SUCCESSFUL in 1s")
                appendLine("$ts [INFO] [daemon] Marking the daemon as idle, address: []")
            },
        )

        WatcherDatabase.open(tmp.resolve("watcher.db")).use { database ->
            database.save(
                Build(
                    buildId = "aged-replay",
                    daemonPid = 9001,
                    daemonIdentity = null,
                    commandLine = null,
                    workingDirectory = "/old",
                    projectPath = "/old",
                    startTimeMs = agedStart,
                    endTimeMs = agedStart + 1_000,
                    durationSeconds = 1.0,
                    peakMemoryMb = null,
                    avgMemoryMb = null,
                    peakCpuPercent = null,
                    inferredSource = Source.UNKNOWN,
                    finalStatus = FinalStatus.SUCCESS,
                    logSnippet = null,
                ),
            )
            database.purgeOlderThan(now, retentionDays = 7)
            assertTrue(database.recent().isEmpty())

            val runtime = WatcherRuntime(
                processSource = collectorWithoutHeapAttach(),
                logSource = DaemonLogWatcher(gradleUserHome = tmp.resolve("gradle")),
                aggregator = BuildAggregator(sampleProvider = database::samples),
                builds = database,
                samples = database,
                retentionDays = { 7 },
                clock = { now },
            )

            assertFalse(runtime.pollOnce().buildsChanged)
            assertTrue(database.recent().isEmpty())
        }
    }
}
