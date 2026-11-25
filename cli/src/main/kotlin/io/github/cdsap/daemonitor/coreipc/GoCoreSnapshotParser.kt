package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.HostContext
import io.github.cdsap.daemonitor.application.MetricCapability
import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.booleanField
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.extractArray
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.extractContainer
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.extractStringArray
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.longField
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.numberField
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.splitObjects
import io.github.cdsap.daemonitor.coreipc.GoCoreJson.stringField
import io.github.cdsap.daemonitor.domain.model.BazelBuildTelemetryParser
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildTool
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.JvmJitMetrics
import io.github.cdsap.daemonitor.domain.model.JvmMemoryPool
import io.github.cdsap.daemonitor.domain.model.JvmRawValue
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import java.nio.file.Path

internal object GoCoreSnapshotParser {
    fun parseHost(json: String): HostContext {
        require(json.trim().startsWith("{") && json.trim().endsWith("}"))
        fun metric(name: String): Double? {
            val obj = Regex("\"$name\"\\s*:\\s*(\\{[^{}]*})").find(json)?.groupValues?.get(1) ?: return null
            return if (stringField(obj, "availability") == "available") numberField(obj, "value") else null
        }
        return HostContext(
            numberField(json, "sampled_at_ms")?.toLong()?.takeIf { it > 0 },
            metric("logical_processors")?.toLong(), metric("cpu_usage_percent"),
            metric("memory_used_bytes")?.toLong(), metric("memory_total_bytes")?.toLong(),
        )
    }

    fun parseCapabilities(json: String): List<MetricCapability> {
        val array = extractArray(json, "metrics") ?: error("Missing capabilities metrics")
        return splitObjects(array).map { obj ->
            MetricCapability(
                stringField(obj, "metric_name") ?: error("Missing metric name"),
                stringField(obj, "availability") ?: error("Missing metric availability"),
            )
        }
    }

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

    fun parseHealth(json: String): GoCoreHealthSnapshot =
        GoCoreHealthSnapshot(
            databasePath = stringField(json, "db_path")?.takeIf { it.isNotBlank() },
            retentionMs = numberField(json, "retention_ms")?.toLong(),
            pid = numberField(json, "pid")?.toLong(),
        )

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
        val tool = stringField(obj, "tool")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BuildTool.valueOf(it.uppercase()) }.getOrNull() }
            ?: BuildTool.GRADLE
        val bazelProfile = extractContainer(obj, "bazel_profile", '{', '}')
            ?.let { BazelBuildTelemetryParser.parseProfile("{$it}") }
        val bazelDetails = extractContainer(obj, "bazel_details", '{', '}')?.let { detailsObj ->
            val wrapped = "{$detailsObj}"
            val metrics = extractContainer(wrapped, "metrics", '{', '}')?.let { "{$it}" }
            BazelBuildTelemetryParser.parseDetails(
                bazelVersion = stringField(wrapped, "bazel_version"),
                sourceFormat = stringField(wrapped, "source_format"),
                collectionState = stringField(wrapped, "collection_state"),
                metricsJson = metrics,
            )
        }
        val windowSummaries = extractContainer(obj, "window_summaries", '[', ']')
            ?.let { BazelBuildTelemetryParser.parseWindowSummaries("[$it]") }
            .orEmpty()
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
            tool = tool,
            bazelProfile = bazelProfile,
            bazelDetails = bazelDetails,
            windowSummaries = windowSummaries,
        )
    }

    private fun String?.nullIfEmpty(): String? = this?.takeIf { it.isNotEmpty() }

    private fun parseDaemonLog(obj: String): DaemonLog? {
        val pid = numberField(obj, "pid")?.toLong() ?: return null
        val path = stringField(obj, "path") ?: return null
        val gradleVersion = stringField(obj, "gradle_version").orEmpty()
        val tool = stringField(obj, "tool")?.takeIf { it.isNotBlank() }
        val source = stringField(obj, "source")?.takeIf { it.isNotBlank() }
        return DaemonLog(
            pid = pid,
            gradleVersion = gradleVersion,
            path = Path.of(path),
            tool = tool,
            source = source,
        )
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
            ownerPid = longField(obj, "owner_pid"),
            ownerStartTimeMs = longField(obj, "owner_start_time_ms"),
            subtreeRssMemoryMb = longField(obj, "subtree_rss_memory_mb"),
            subtreeCpuPercent = numberField(obj, "subtree_cpu_percent"),
            activeWorkerCount = longField(obj, "active_worker_count"),
            activeSandboxedActionCount = longField(obj, "active_sandboxed_action_count"),
            bazelOutputBase = stringField(obj, "bazel_output_base")?.takeIf { it.isNotBlank() },
            bazelInstallBase = stringField(obj, "bazel_install_base")?.takeIf { it.isNotBlank() },
            type = type,
            commandLine = commandLine,
            workingDirectory = workingDirectory,
            projectPath = projectPath,
            cpuPercent = cpu,
            cpuUserSecondsTotal = numberField(obj, "cpu_user_seconds_total"),
            cpuSystemSecondsTotal = numberField(obj, "cpu_system_seconds_total"),
            cpuTotalSecondsTotal = numberField(obj, "cpu_total_seconds_total"),
            cpuCoresUsed = numberField(obj, "cpu_cores_used"),
            logicalProcessorCount = longField(obj, "logical_processor_count"),
            resourceScope = stringField(obj, "resource_scope")?.takeIf { it.isNotBlank() },
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
            proportionalBytes = longField(obj, "proportional_bytes"),
            privateCommittedBytes = longField(obj, "private_committed_bytes"),
            physicalFootprintBytes = longField(obj, "physical_footprint_bytes"),
            peakResidentBytes = longField(obj, "peak_resident_bytes"),
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
            memoryPools = parsePools(obj, "memory_pools"),
            codeCache = parsePools(obj, "code_cache"),
            jit = extractContainer(obj, "jit", '{', '}')?.let {
                JvmJitMetrics(longField(it, "compiled_count"), longField(it, "failed_count"),
                    longField(it, "invalid_count"), numberField(it, "compilation_time_seconds"))
            },
        )
    }

    private fun parsePools(obj: String, field: String): List<JvmMemoryPool> =
        extractContainer(obj, field, '[', ']')?.let(::splitObjects).orEmpty().map { pool ->
            fun value(name: String): JvmRawValue? = extractContainer(pool, name, '{', '}')?.let {
                val number = numberField(it, "value") ?: return@let null
                val unit = stringField(it, "unit") ?: return@let null
                JvmRawValue(number, unit)
            }
            JvmMemoryPool(stringField(pool, "name").orEmpty(), stringField(pool, "source"),
                value("used"), value("committed"), value("max"))
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
}
