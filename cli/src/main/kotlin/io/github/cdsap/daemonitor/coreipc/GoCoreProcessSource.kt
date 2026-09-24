package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Experimental [ProcessSource] that reads `/v1/processes` from `daemonitor-cored`
 * over a Unix-domain socket (Go core IPC spike).
 *
 * Live JVM heap is intentionally always `null` here — Attach/JMX stays on the in-process
 * Kotlin collector. Product cutover accepts that gap (see `cored/docs/dual-run.md`).
 */
class GoCoreProcessSource(
    private val socketPath: Path,
    private val fetch: (Path, String) -> String = ::unixHttpGet,
) : ProcessSource {
    override fun currentProcesses(): List<GradleProcess> {
        requireGoCoreSocket(socketPath)
        val body = fetch(socketPath, "/v1/processes")
        return GoCoreSnapshotParser.parseProcesses(body)
    }
}

internal object GoCoreSnapshotParser {
    fun parseProcesses(json: String): List<GradleProcess> {
        val arrayBody = extractArray(json, "processes") ?: return emptyList()
        return splitObjects(arrayBody).mapNotNull { parseProcess(it) }
    }

    fun parseDaemonLogs(json: String): List<DaemonLog> {
        val arrayBody = extractArray(json, "logs") ?: return emptyList()
        return splitObjects(arrayBody).mapNotNull { parseDaemonLog(it) }
    }

    fun parseDaemonLogTail(json: String): List<String> =
        extractStringArray(json, "lines").orEmpty()

    fun parseBuilds(json: String): List<Build> {
        val arrayBody = extractArray(json, "builds") ?: return emptyList()
        return splitObjects(arrayBody).mapNotNull { parseBuild(it) }
    }

    /** Absolute DB path from `/v1/health` (`db_path`), or null when absent. */
    fun parseHealthDbPath(json: String): String? =
        stringField(json, "db_path")?.takeIf { it.isNotBlank() }

    private fun parseBuild(obj: String): Build? {
        val buildId = stringField(obj, "build_id") ?: return null
        val daemonPid = numberField(obj, "daemon_pid")?.toLong() ?: return null
        val startTimeMs = numberField(obj, "start_time_ms")?.toLong() ?: return null
        val inferredSource = stringField(obj, "inferred_source")
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            ?: Source.UNKNOWN
        val finalStatus = stringField(obj, "final_status")
            ?.let { runCatching { FinalStatus.valueOf(it) }.getOrNull() }
            ?: return null
        return Build(
            buildId = buildId,
            daemonPid = daemonPid,
            daemonIdentity = stringField(obj, "daemon_identity").nullIfEmpty(),
            commandLine = stringField(obj, "command_line"),
            workingDirectory = stringField(obj, "working_directory").nullIfEmpty(),
            projectPath = stringField(obj, "project_path").nullIfEmpty(),
            startTimeMs = startTimeMs,
            endTimeMs = numberField(obj, "end_time_ms")?.toLong(),
            durationSeconds = numberField(obj, "duration_seconds"),
            peakMemoryMb = numberField(obj, "peak_memory_mb")?.toLong(),
            avgMemoryMb = numberField(obj, "avg_memory_mb")?.toLong(),
            peakCpuPercent = numberField(obj, "peak_cpu_percent"),
            inferredSource = inferredSource,
            finalStatus = finalStatus,
            logSnippet = stringField(obj, "log_snippet").nullIfEmpty(),
            agent = stringField(obj, "agent").nullIfEmpty(),
            agentProvider = stringField(obj, "agent_provider").nullIfEmpty(),
        )
    }

    private fun String?.nullIfEmpty(): String? = this?.takeIf { it.isNotEmpty() }

    private fun parseDaemonLog(obj: String): DaemonLog? {
        val pid = numberField(obj, "pid")?.toLong() ?: return null
        val gradleVersion = stringField(obj, "gradle_version") ?: return null
        val path = stringField(obj, "path") ?: return null
        return DaemonLog(pid = pid, gradleVersion = gradleVersion, path = Path.of(path))
    }

    private fun parseProcess(obj: String): GradleProcess? {
        val pid = numberField(obj, "pid")?.toLong() ?: return null
        val typeName = stringField(obj, "type") ?: return null
        val type = runCatching { ProcessType.valueOf(typeName) }.getOrNull() ?: return null
        val commandLine = stringField(obj, "command_line").orEmpty()
        val rss = numberField(obj, "rss_memory_mb")?.toLong() ?: 0L
        val cpu = numberField(obj, "cpu_percent")
        val parentPid = numberField(obj, "parent_pid")?.toLong() ?: 0L
        val maxHeap = numberField(obj, "max_heap_mb")?.toLong()
        val minHeap = numberField(obj, "min_heap_mb")?.toLong()
        val gc = stringField(obj, "gc")
        val startTimeMs = numberField(obj, "start_time_ms")?.toLong() ?: 0L
        val status = stringField(obj, "status") ?: "RUNNING"
        val automated = booleanField(obj, "automated") ?: false
        val workingDirectory = stringField(obj, "working_directory")
        val projectPath = stringField(obj, "project_path")
        return GradleProcess(
            pid = pid,
            parentPid = parentPid,
            type = type,
            commandLine = commandLine,
            workingDirectory = workingDirectory,
            projectPath = projectPath,
            cpuPercent = cpu,
            rssMemoryMb = rss,
            maxHeapMb = maxHeap,
            minHeapMb = minHeap,
            gc = gc,
            startTimeMs = startTimeMs,
            status = status,
            automated = automated,
            liveHeap = null,
        )
    }

    private fun extractArray(json: String, name: String): String? {
        val key = "\"$name\""
        val keyIndex = json.indexOf(key)
        if (keyIndex < 0) return null
        val start = json.indexOf('[', keyIndex)
        if (start < 0) return null
        var depth = 0
        for (i in start until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start + 1, i)
                }
            }
        }
        return null
    }

    private fun extractStringArray(json: String, name: String): List<String>? {
        val arrayBody = extractArray(json, name) ?: return null
        val out = mutableListOf<String>()
        val regex = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        for (match in regex.findAll(arrayBody)) {
            out += match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return out
    }

    private fun splitObjects(arrayBody: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in arrayBody.indices) {
            when (arrayBody[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += arrayBody.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun stringField(obj: String, name: String): String? {
        val key = "\"$name\""
        var searchFrom = 0
        while (true) {
            val keyIndex = obj.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var i = keyIndex + key.length
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') {
                searchFrom = keyIndex + 1
                continue
            }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            if (obj.startsWith("null", i) &&
                (i + 4 == obj.length || obj[i + 4] in ",}] \r\n\t")
            ) {
                return null
            }
            if (obj[i] != '"') return null
            return decodeJsonString(obj, i + 1)
        }
    }

    /** Linear scan — avoids regex StackOverflowError on multi-KB Gradle command lines. */
    private fun decodeJsonString(source: String, start: Int): String {
        val out = StringBuilder()
        var i = start
        while (i < source.length) {
            when (val c = source[i]) {
                '"' -> return out.toString()
                '\\' -> {
                    i++
                    if (i >= source.length) break
                    when (val escaped = source[i]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'u' -> if (i + 4 < source.length) {
                            val code = source.substring(i + 1, i + 5).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 4
                            }
                        }
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun numberField(obj: String, name: String): Double? {
        val key = "\"$name\""
        var searchFrom = 0
        while (true) {
            val keyIndex = obj.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var i = keyIndex + key.length
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') {
                searchFrom = keyIndex + 1
                continue
            }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            if (obj.startsWith("null", i)) return null
            val start = i
            if (obj[i] == '-') i++
            while (i < obj.length && (obj[i].isDigit() || obj[i] == '.')) i++
            return obj.substring(start, i).toDoubleOrNull()
        }
    }

    private fun booleanField(obj: String, name: String): Boolean? {
        val key = "\"$name\""
        var searchFrom = 0
        while (true) {
            val keyIndex = obj.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var i = keyIndex + key.length
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') {
                searchFrom = keyIndex + 1
                continue
            }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            return when {
                obj.startsWith("true", i) -> true
                obj.startsWith("false", i) -> false
                else -> null
            }
        }
    }
}

internal fun requireGoCoreSocket(socketPath: Path) {
    if (!socketPath.exists()) {
        throw GoCoreUnavailableException(
            "Go core socket not found: $socketPath (is daemonitor-cored running?)",
        )
    }
}

/** Bound connect/read so a wedged cored cannot freeze the CLI poll loop indefinitely. */
internal const val UNIX_HTTP_TIMEOUT_MS = 5_000

internal fun unixHttpGet(
    socketPath: Path,
    path: String,
    timeoutMs: Int = UNIX_HTTP_TIMEOUT_MS,
): String {
    requireGoCoreSocket(socketPath)
    return try {
        val address = UnixDomainSocketAddress.of(socketPath)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            if (!channel.connect(address)) {
                awaitChannel(channel, SelectionKey.OP_CONNECT, timeoutMs, "connect")
                if (!channel.finishConnect()) {
                    throw SocketTimeoutException("connect timed out after ${timeoutMs}ms")
                }
            }
            val request = "GET $path HTTP/1.0\r\nHost: localhost\r\nAccept: application/json\r\n\r\n"
            writeFully(channel, ByteBuffer.wrap(request.toByteArray(StandardCharsets.US_ASCII)), timeoutMs)
            val text = readUntilEof(channel, timeoutMs)
            val split = text.indexOf("\r\n\r\n")
            require(split >= 0) { "Go core returned a malformed HTTP response" }
            val statusLine = text.lineSequence().firstOrNull().orEmpty()
            require(statusLine.contains(" 200 ") || statusLine.endsWith(" 200")) {
                "Go core request failed: $statusLine"
            }
            text.substring(split + 4)
        }
    } catch (e: GoCoreUnavailableException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: SocketTimeoutException) {
        throw GoCoreUnavailableException(
            "Go core timed out at $socketPath after ${timeoutMs}ms (is daemonitor-cored stuck?): ${e.message}",
            e,
        )
    } catch (e: InterruptedIOException) {
        throw GoCoreUnavailableException(
            "Go core unreachable at $socketPath (is daemonitor-cored running, or is the socket stale?): ${e.message}",
            e,
        )
    } catch (e: Exception) {
        throw GoCoreUnavailableException(
            "Go core unreachable at $socketPath (is daemonitor-cored running, or is the socket stale?): ${e.message}",
            e,
        )
    }
}

private fun writeFully(channel: SocketChannel, buffer: ByteBuffer, timeoutMs: Int) {
    while (buffer.hasRemaining()) {
        awaitChannel(channel, SelectionKey.OP_WRITE, timeoutMs, "write")
        if (channel.write(buffer) < 0) {
            throw SocketTimeoutException("write failed after ${timeoutMs}ms")
        }
    }
}

private fun readUntilEof(channel: SocketChannel, timeoutMs: Int): String {
    val buffer = ByteBuffer.allocate(64 * 1024)
    val raw = StringBuilder()
    while (true) {
        awaitChannel(channel, SelectionKey.OP_READ, timeoutMs, "read")
        val read = channel.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        raw.append(String(bytes, StandardCharsets.UTF_8))
        buffer.clear()
    }
    return raw.toString()
}

private fun awaitChannel(channel: SocketChannel, ops: Int, timeoutMs: Int, opName: String) {
    Selector.open().use { selector ->
        val key = channel.register(selector, ops)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (true) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0L) {
                    throw SocketTimeoutException("$opName timed out after ${timeoutMs}ms")
                }
                val selected = selector.select(remaining)
                if (selected > 0 && key.isValid && key.readyOps() and ops != 0) {
                    return
                }
                selector.selectedKeys().clear()
            }
        } finally {
            key.cancel()
            selector.selectNow()
        }
    }
}
