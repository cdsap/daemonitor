package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import java.nio.file.Path

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
            liveHeap = parseLiveHeap(obj),
            virtualMemoryMb = longField(obj, "virtual_memory_mb"),
            swapMemoryMb = longField(obj, "swap_memory_mb"),
            threadCount = longField(obj, "thread_count"),
            readBytes = longField(obj, "read_bytes"),
            writeBytes = longField(obj, "write_bytes"),
            readOperations = longField(obj, "read_operations"),
            writeOperations = longField(obj, "write_operations"),
            minorPageFaults = longField(obj, "minor_page_faults"),
            majorPageFaults = longField(obj, "major_page_faults"),
            voluntaryContextSwitches = longField(obj, "voluntary_context_switches"),
            involuntaryContextSwitches = longField(obj, "involuntary_context_switches"),
            openFileDescriptors = longField(obj, "open_file_descriptors"),
            metaspaceUsedMb = longField(obj, "metaspace_used_mb"),
            metaspaceCommittedMb = longField(obj, "metaspace_committed_mb"),
            youngGcCount = longField(obj, "young_gc_count"),
            youngGcTimeMs = longField(obj, "young_gc_time_ms"),
            youngGcTimeSeconds = numberField(obj, "young_gc_time_seconds"),
            fullGcTimeSeconds = numberField(obj, "full_gc_time_seconds"),
            concurrentGcTimeSeconds = numberField(obj, "concurrent_gc_time_seconds"),
            totalGcTimeSeconds = numberField(obj, "total_gc_time_seconds"),
            oldGcCount = longField(obj, "old_gc_count"),
            oldGcTimeMs = longField(obj, "old_gc_time_ms"),
            javaVersion = stringField(obj, "java_version"),
            javaRuntimeVersion = stringField(obj, "java_runtime_version"),
            javaVendor = stringField(obj, "java_vendor"),
            javaVmName = stringField(obj, "java_vm_name"),
            javaVmVersion = stringField(obj, "java_vm_version"),
            osName = stringField(obj, "os_name"),
            osArch = stringField(obj, "os_arch"),
            activeProcessorCount = longField(obj, "active_processor_count"),
        )
    }

    /**
     * Maps Go `/v1/processes` heap_* fields onto [LiveJvmHeap].
     * Missing fields (older cored) → null; `heap_available: false` → unavailable (not zero).
     */
    internal fun parseLiveHeap(obj: String): LiveJvmHeap? {
        val available = booleanField(obj, "heap_available")
        val sampledAt = numberField(obj, "heap_sampled_at_ms")?.toLong()
        val used = numberField(obj, "heap_used_mb")?.toLong()
        val committed = numberField(obj, "heap_committed_mb")?.toLong()
        val max = numberField(obj, "heap_max_mb")?.toLong()
        if (available == null && used == null && committed == null && sampledAt == null) {
            return null
        }
        if (available != true) {
            return LiveJvmHeap.unavailable(sampledAt ?: 0L)
        }
        return LiveJvmHeap(
            usedMb = used,
            committedMb = committed,
            maxMb = max,
            sampledAtMs = sampledAt ?: 0L,
            available = true,
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

    private fun longField(obj: String, name: String): Long? = numberField(obj, name)?.toLong()

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
