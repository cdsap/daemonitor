package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.application.DaemonLogParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
        assertTrue(watcher.readNewLines(log).isEmpty(), "partial line must not be returned yet")
        // Complete the line.
        Files.writeString(log, "n 5s\n", java.nio.file.StandardOpenOption.APPEND)
        val lines = watcher.readNewLines(log)
        assertTrue(lines.any { DaemonLogParser.parseLine(it.text) != null }, "completed line must be returned")
    }

    @Test
    fun `reads only newly appended content and redacts it`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-1.out.log")
        log.writeText("BUILD SUCCESSFUL in 3s\n")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp)

        val first = watcher.readNewLines(log)
        assertTrue(first.any { DaemonLogParser.parseLine(it.text) != null })

        // No new content → no new events.
        assertTrue(watcher.readNewLines(log).isEmpty())

        // Append a secret-bearing line; confirm it is redacted in the tail.
        Files.writeString(
            log,
            "2026-06-24T10:00:00.000-0700 [INFO] [Build] running -Ptoken=topsecret\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
        watcher.readNewLines(log)
        assertTrue(watcher.tailFor(log).any { it.contains("-Ptoken=***") })
        assertTrue(watcher.tailFor(log).none { it.contains("topsecret") })
    }

    @Test
    fun `initial scan reads only a bounded tail of a large existing log`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-large.out.log")
        Files.newByteChannel(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.position(Int.MAX_VALUE.toLong() + 1024)
            channel.write(java.nio.ByteBuffer.wrap("ignored fragment\nBUILD SUCCESSFUL in 2s\n".toByteArray()))
        }
        val watcher = DaemonLogWatcher(gradleUserHome = tmp, initialReadBytes = 64, readChunkBytes = 7)

        val lines = watcher.readNewLines(log)

        assertTrue(lines.any { DaemonLogParser.parseLine(it.text) != null })
        assertEquals(listOf("BUILD SUCCESSFUL in 2s"), watcher.tailFor(log))
    }

    @Test
    fun `chunk boundaries preserve split utf8 and partial lines`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-chunks.out.log")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp, readChunkBytes = 2)
        log.writeText("message café")

        assertTrue(watcher.readNewLines(log).isEmpty())
        Files.writeString(log, "\nBUILD SUCCESSFUL in 1s\n", StandardOpenOption.APPEND)

        assertTrue(watcher.readNewLines(log).any { DaemonLogParser.parseLine(it.text) != null })
        assertEquals(listOf("message café", "BUILD SUCCESSFUL in 1s"), watcher.tailFor(log))
    }

    @Test
    fun `truncation clears stale partial content before reading replacement`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-truncated.out.log")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp, readChunkBytes = 3)
        log.writeText("stale partial line that is substantially longer than its replacement")
        assertTrue(watcher.readNewLines(log).isEmpty())

        log.writeText("BUILD FAILED in 1s\n")
        assertTrue(watcher.readNewLines(log).isEmpty(), "truncation is observed before replacement data")

        val lines = watcher.readNewLines(log)
        assertTrue(lines.any { DaemonLogParser.parseLine(it.text) != null })
        assertEquals(listOf("BUILD FAILED in 1s"), watcher.tailFor(log))
    }

    @Test
    fun `appended event is read after bounded initialization`(@TempDirArg tmp: Path) {
        val log = tmp.resolve("daemon-appended.out.log")
        log.writeText("old line\n")
        val watcher = DaemonLogWatcher(gradleUserHome = tmp, initialReadBytes = 4, readChunkBytes = 2)
        watcher.readNewLines(log)

        Files.writeString(log, "BUILD SUCCESSFUL in 4s\n", StandardOpenOption.APPEND)

        assertTrue(watcher.readNewLines(log).any { DaemonLogParser.parseLine(it.text) != null })
    }
}

// JUnit5 @TempDir shim usable from kotlin.test style without importing the annotation everywhere.
private typealias TempDirArg = org.junit.jupiter.api.io.TempDir
