package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.platform.AppDirectories
import io.github.cdsap.daemonitor.domain.Redactor
import io.github.cdsap.daemonitor.domain.model.BuildEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A discovered daemon log file with its PID and originating Gradle version. */
data class DaemonLog(val pid: Long, val gradleVersion: String, val path: Path)

/** One complete, redacted daemon-log line and the build event parsed from that same line. */
data class DaemonLogLine(val text: String, val event: BuildEvent?)

/**
 * Locates daemon logs, reads new content incrementally (offset-based, cheaper than re-tailing),
 * and parses build events (U3). Redaction (KTD-7) runs on every line before it enters the tail
 * buffer or is parsed, so anything exposed downstream is already scrubbed.
 *
 * Implements [DaemonLogSource] so application polling depends on the port, not filesystem details.
 *
 * macOS note: `WatchService` has no FSEvents backend (polling fallback); newly created version
 * subdirectories are picked up by re-running [discover] each poll rather than relying on a
 * single registration.
 */
class DaemonLogWatcher(
    private val gradleUserHome: Path = AppDirectories.system.gradleUserHome,
    private val tailLines: Int = MonitoringConfig.DEFAULT.logTailLines,
    private val initialReadBytes: Int = DEFAULT_INITIAL_READ_BYTES,
    private val readChunkBytes: Int = DEFAULT_READ_CHUNK_BYTES,
) : DaemonLogSource {
    init {
        require(initialReadBytes > 0) { "initialReadBytes must be positive" }
        require(readChunkBytes > 0) { "readChunkBytes must be positive" }
    }

    private val offsets = mutableMapOf<Path, Long>()
    private val tails = mutableMapOf<Path, ArrayDeque<String>>()
    /** Bytes after the last newline of the previous read, carried so a line split across two
     *  reads (or a multi-byte char split at the boundary) is reassembled, not lost. */
    private val leftovers = mutableMapOf<Path, ByteArray>()

    /** Scan `<gradleUserHome>/daemon/<version>/daemon-<pid>.out.log` for current daemon logs. */
    override fun discover(): List<DaemonLog> {
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
     * The first read of an existing file starts at a bounded tail and drops an initial partial
     * line. Later reads consume all appended content in bounded chunks. Lines are redacted before
     * parsing and before being retained for the live tail.
     */
    fun readNewEvents(path: Path): List<BuildEvent> =
        readNewLines(path).mapNotNull { it.event }

    override fun readNewLines(log: DaemonLog): List<DaemonLogLine> = readNewLines(log.path)

    /**
     * Read newly appended complete lines while retaining their parsed-event association. This lets
     * downstream build correlation assign each redacted line to the exact busy-to-idle window.
     */
    fun readNewLines(path: Path): List<DaemonLogLine> {
        if (!path.exists()) return emptyList()
        val size = Files.size(path)
        val initialized = offsets.containsKey(path)
        val from = offsets[path] ?: (size - initialReadBytes.toLong()).coerceAtLeast(0L)
        if (size < from) {
            // File truncated/rotated → reset and drop any stale partial.
            offsets[path] = 0L
            leftovers.remove(path)
            return emptyList()
        }
        if (size == from) return emptyList()

        val lines = mutableListOf<String>()
        val partial = ByteArrayOutputStream()
        leftovers.remove(path)?.let(partial::write)

        Files.newByteChannel(path).use { ch ->
            ch.position(from)
            var discardInitialFragment = !initialized && from > 0L && !startsAtLineBoundary(ch, from)
            val buffer = ByteBuffer.allocate(readChunkBytes)
            while (ch.position() < size) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), size - ch.position()).toInt())
                if (ch.read(buffer) <= 0) break
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val byte = buffer.get()
                    if (discardInitialFragment) {
                        if (byte == NEWLINE) discardInitialFragment = false
                    } else if (byte == NEWLINE) {
                        val bytes = partial.toByteArray()
                        val lineLength = if (bytes.lastOrNull() == CARRIAGE_RETURN) bytes.size - 1 else bytes.size
                        if (lineLength > 0) lines += String(bytes, 0, lineLength, Charsets.UTF_8)
                        partial.reset()
                    } else {
                        partial.write(byte.toInt())
                    }
                }
            }
            offsets[path] = ch.position()
        }
        if (partial.size() > 0) leftovers[path] = partial.toByteArray()

        val redacted = lines.asSequence()
            .map { Redactor.redactLogLine(it) }
            .toList()

        retainTail(path, redacted)
        return redacted.map { DaemonLogLine(it, DaemonLogParser.parseLine(it)) }
    }

    override fun tailFor(log: DaemonLog): List<String> = tailFor(log.path)

    /** The last [tailLines] redacted lines seen for a log, for the live tail panel (U7). */
    fun tailFor(path: Path): List<String> = tails[path]?.toList() ?: emptyList()

    private fun retainTail(path: Path, lines: List<String>) {
        val deque = tails.getOrPut(path) { ArrayDeque() }
        for (line in lines) {
            deque.addLast(line)
            while (deque.size > tailLines) deque.removeFirst()
        }
    }

    private fun startsAtLineBoundary(channel: SeekableByteChannel, from: Long): Boolean {
        val current = channel.position()
        val previous = ByteBuffer.allocate(1)
        channel.position(from - 1)
        val atBoundary = channel.read(previous) == 1 && previous.array()[0] == NEWLINE
        channel.position(current)
        return atBoundary
    }

    companion object {
        private const val DEFAULT_INITIAL_READ_BYTES = 256 * 1024
        private const val DEFAULT_READ_CHUNK_BYTES = 16 * 1024
        private val NEWLINE = '\n'.code.toByte()
        private val CARRIAGE_RETURN = '\r'.code.toByte()
        private val FILENAME = Regex("""^daemon-(\d+)\.out\.log$""")

        /** Pure: map a daemon log path to its PID + Gradle version (the parent directory name). */
        fun parseLogPath(path: Path): DaemonLog? {
            val m = FILENAME.matchEntire(path.name) ?: return null
            val version = path.parent?.name ?: return null
            return DaemonLog(pid = m.groupValues[1].toLong(), gradleVersion = version, path = path)
        }
    }
}
