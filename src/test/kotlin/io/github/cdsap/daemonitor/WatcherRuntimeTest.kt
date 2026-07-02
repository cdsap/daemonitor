package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.collect.DaemonLogWatcher
import io.github.cdsap.daemonitor.domain.BuildAggregator
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
                logWatcher = DaemonLogWatcher(gradleUserHome = tmp.resolve("gradle")),
                aggregator = BuildAggregator(sampleProvider = database::samplesInWindow),
                database = database,
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

            val build = database.recentBuilds().single()
            val snippet = assertNotNull(build.logSnippet)
            assertTrue(snippet.contains("-Ptoken=***"))
            assertFalse(snippet.contains("window-secret"))
            assertFalse(snippet.contains("outside before window"))
            assertFalse(snippet.contains("outside after window"))
            assertEquals(5, snippet.lines().size)
        }
    }
}
