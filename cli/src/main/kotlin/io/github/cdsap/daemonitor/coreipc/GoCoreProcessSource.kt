package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Experimental [ProcessSource] that reads `/v1/processes` from `daemonitor-cored`
 * over a Unix-domain socket (Go core IPC spike).
 */
class GoCoreProcessSource(
    private val socketPath: Path,
    private val fetch: (Path, String) -> String = ::unixHttpGet,
) : ProcessSource {
    override fun currentProcesses(): List<GradleProcess> {
        require(socketPath.exists()) {
            "Go core socket not found: $socketPath (is daemonitor-cored running?)"
        }
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
        val nullRegex = Regex(""""$name"\s*:\s*null""")
        if (nullRegex.containsMatchIn(obj)) return null
        val regex = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val match = regex.find(obj) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    private fun numberField(obj: String, name: String): Double? {
        val nullRegex = Regex(""""$name"\s*:\s*null""")
        if (nullRegex.containsMatchIn(obj)) return null
        val regex = Regex(""""$name"\s*:\s*(-?\d+(?:\.\d+)?)""")
        return regex.find(obj)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun booleanField(obj: String, name: String): Boolean? {
        val regex = Regex(""""$name"\s*:\s*(true|false)""")
        return regex.find(obj)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }
}

internal fun unixHttpGet(socketPath: Path, path: String): String {
    val address = UnixDomainSocketAddress.of(socketPath)
    SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        channel.connect(address)
        val request = "GET $path HTTP/1.0\r\nHost: localhost\r\nAccept: application/json\r\n\r\n"
        channel.write(ByteBuffer.wrap(request.toByteArray(StandardCharsets.US_ASCII)))
        val buffer = ByteBuffer.allocate(64 * 1024)
        val raw = StringBuilder()
        while (channel.read(buffer) > 0) {
            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            raw.append(String(bytes, StandardCharsets.UTF_8))
            buffer.clear()
        }
        val text = raw.toString()
        val split = text.indexOf("\r\n\r\n")
        require(split >= 0) { "Go core returned a malformed HTTP response" }
        val statusLine = text.lineSequence().firstOrNull().orEmpty()
        require(statusLine.contains(" 200 ") || statusLine.endsWith(" 200")) {
            "Go core request failed: $statusLine"
        }
        return text.substring(split + 4)
    }
}
