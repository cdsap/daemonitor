package com.gradlewatcher.collect

import com.gradlewatcher.domain.model.Outcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonLogWatcherTest {

    @Test
    fun `parses pid and version from log path`() {
        val p = Path.of("/Users/dev/.gradle/daemon/8.9/daemon-12914.out.log")
        val log = DaemonLogWatcher.parseLogPath(p)!!
        assertEquals(12914L, log.pid)
        assertEquals("8.9", log.gradleVersion)
    }

    @Test
    fun `non-daemon files are not matched`() {
        assertEquals(null, DaemonLogWatcher.parseLogPath(Path.of("/x/8.9/registry.bin")))
    }

    @Test
    fun `discovers daemon logs under gradle user home`(@TempDirArg tmp: Path) {
        val versionDir = tmp.resolve("daemon/8.14.3").also { it.createDirectories() }
        versionDir.resolve("daemon-555.out.log").writeText("hello\n")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp)
        val logs = watcher.discover()
        assertEquals(1, logs.size)
        assertEquals(555L, logs.single().pid)
    }

    @Test
    fun `reassembles a line split across two reads`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-2.out.log")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp)
        // First write: a partial outcome line with no trailing newline.
        log.writeText("BUILD SUCCESSFUL i")
        assertTrue(watcher.readNewEvents(log).isEmpty(), "partial line must not parse yet")
        // Complete the line.
        Files.writeString(log, "n 5s\n", java.nio.file.StandardOpenOption.APPEND)
        val events = watcher.readNewEvents(log)
        assertTrue(events.any { it is Outcome }, "completed line must parse exactly once")
    }

    @Test
    fun `reads only newly appended content and redacts it`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-1.out.log")
        log.writeText("BUILD SUCCESSFUL in 3s\n")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp)

        val first = watcher.readNewEvents(log)
        assertTrue(first.any { it is Outcome })

        // No new content → no new events.
        assertTrue(watcher.readNewEvents(log).isEmpty())

        // Append a secret-bearing line; confirm it is redacted in the tail.
        Files.writeString(
            log,
            "2026-06-24T10:00:00.000-0700 [INFO] [Build] running -Ptoken=topsecret\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
        watcher.readNewEvents(log)
        assertTrue(watcher.tailFor(log).any { it.contains("-Ptoken=***") })
        assertTrue(watcher.tailFor(log).none { it.contains("topsecret") })
    }
}

// JUnit5 @TempDir shim usable from kotlin.test style without importing the annotation everywhere.
private typealias TempDirArg = org.junit.jupiter.api.io.TempDir
