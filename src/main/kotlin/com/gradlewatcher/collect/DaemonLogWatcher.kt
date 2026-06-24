package com.gradlewatcher.collect

import com.gradlewatcher.Defaults
import com.gradlewatcher.domain.Redactor
import com.gradlewatcher.domain.model.BuildEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A discovered daemon log file with its PID and originating Gradle version. */
data class DaemonLog(val pid: Long, val gradleVersion: String, val path: Path)

/**
 * Locates daemon logs, reads new content incrementally (offset-based, cheaper than re-tailing),
 * and parses build events (U3). Redaction (KTD-7) runs on every line before it enters the tail
 * buffer or is parsed, so anything exposed downstream is already scrubbed.
 *
 * macOS note: `WatchService` has no FSEvents backend (polling fallback); newly created version
 * subdirectories are picked up by re-running [discover] each poll rather than relying on a
 * single registration.
 */
class DaemonLogWatcher(
    private val gradleUserHome: Path = Defaults.GRADLE_USER_HOME,
    private val tailLines: Int = Defaults.LOG_TAIL_LINES,
) {
    private val offsets = mutableMapOf<Path, Long>()
    private val tails = mutableMapOf<Path, ArrayDeque<String>>()
    /** Bytes after the last newline of the previous read, carried so a line split across two
     *  reads (or a multi-byte char split at the boundary) is reassembled, not lost. */
    private val leftovers = mutableMapOf<Path, ByteArray>()

    /** Scan `<gradleUserHome>/daemon/<version>/daemon-<pid>.out.log` for current daemon logs. */
    fun discover(): List<DaemonLog> {
        val daemonRoot = gradleUserHome.resolve("daemon")
        if (!daemonRoot.exists() || !daemonRoot.isDirectory()) return emptyList()
        return daemonRoot.listDirectoryEntries()
            .filter { it.isDirectory() }
            .flatMap { versionDir ->
                versionDir.listDirectoryEntries("daemon-*.out.log")
                    .mapNotNull { parseLogPath(it) }
            }
    }

    /**
     * Read whatever has been appended to [path] since the last call, returning the parsed events.
     * Lines are redacted before parsing and before being retained for the live tail.
     */
    fun readNewEvents(path: Path): List<BuildEvent> {
        if (!path.exists()) return emptyList()
        val size = Files.size(path)
        val from = offsets[path] ?: 0L
        if (size < from) {
            // File truncated/rotated → reset and drop any stale partial.
            offsets[path] = 0L
            leftovers.remove(path)
            return emptyList()
        }
        if (size == from) return emptyList()

        // Read the full new range (looping to handle short reads).
        val fresh = Files.newByteChannel(path).use { ch ->
            ch.position(from)
            val buf = java.nio.ByteBuffer.allocate((size - from).toInt())
            while (buf.hasRemaining() && ch.read(buf) > 0) { /* keep reading */ }
            buf.flip()
            ByteArray(buf.remaining()).also { buf.get(it) }
        }
        offsets[path] = from + fresh.size

        // Prepend bytes carried from the previous read, then split off the trailing partial line.
        val combined = (leftovers[path] ?: ByteArray(0)) + fresh
        val lastNewline = combined.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) {
            leftovers[path] = combined // no complete line yet
            return emptyList()
        }
        leftovers[path] = combined.copyOfRange(lastNewline + 1, combined.size)
        val completeText = String(combined, 0, lastNewline + 1, Charsets.UTF_8)

        val redacted = completeText.lineSequence()
            .filter { it.isNotEmpty() }
            .map { Redactor.redactLogLine(it) }
            .toList()

        retainTail(path, redacted)
        return DaemonLogParser.parse(redacted.asSequence())
    }

    /** The last [tailLines] redacted lines seen for a log, for the live tail panel (U7). */
    fun tailFor(path: Path): List<String> = tails[path]?.toList() ?: emptyList()

    private fun retainTail(path: Path, lines: List<String>) {
        val deque = tails.getOrPut(path) { ArrayDeque() }
        for (line in lines) {
            deque.addLast(line)
            while (deque.size > tailLines) deque.removeFirst()
        }
    }

    companion object {
        private val FILENAME = Regex("""^daemon-(\d+)\.out\.log$""")

        /** Pure: map a daemon log path to its PID + Gradle version (the parent directory name). */
        fun parseLogPath(path: Path): DaemonLog? {
            val m = FILENAME.matchEntire(path.name) ?: return null
            val version = path.parent?.name ?: return null
            return DaemonLog(pid = m.groupValues[1].toLong(), gradleVersion = version, path = path)
        }
    }
}
