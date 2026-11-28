package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLog
import io.github.cdsap.daemonitor.application.HostContext
import io.github.cdsap.daemonitor.application.MetricCapability
import io.github.cdsap.daemonitor.domain.model.BazelBuildTelemetryParser
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildTool
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.JvmClassMetrics
import io.github.cdsap.daemonitor.domain.model.JvmJitMetrics
import io.github.cdsap.daemonitor.domain.model.JvmMemoryPool
import io.github.cdsap.daemonitor.domain.model.JvmRawValue
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.json.Json
import io.github.cdsap.daemonitor.json.JsonObject
import io.github.cdsap.daemonitor.json.JsonString
import java.nio.file.Path

internal object GoCoreSnapshotParser {
    fun parseHost(json: String): HostContext {
        val root = Json.parse(json) as JsonObject
        fun metric(name: String): JsonObject? =
            root.obj(name)?.takeIf { it.string("availability") == "available" }
        return HostContext(
            root.long("sampled_at_ms")?.takeIf { it > 0 },
            metric("logical_processors")?.long("value"), metric("cpu_usage_percent")?.number("value"),
            metric("memory_used_bytes")?.long("value"), metric("memory_total_bytes")?.long("value"),
        )
    }

    fun parseCapabilities(json: String): List<MetricCapability> {
        val array = (Json.parse(json) as? JsonObject)?.array("metrics") ?: error("Missing capabilities metrics")
        return array.values.filterIsInstance<JsonObject>().map { obj ->
            MetricCapability(
                obj.string("metric_name") ?: error("Missing metric name"),
                obj.string("availability") ?: error("Missing metric availability"),
            )
        }
    }

    fun parseProcesses(json: String): List<GradleProcess> {
        val array = (Json.parse(json) as? JsonObject)?.array("processes") ?: return emptyList()
        return array.values.filterIsInstance<JsonObject>().mapNotNull { parseProcess(it) }
    }

    fun parseDaemonLogs(json: String): List<DaemonLog> {
        val array = (Json.parse(json) as? JsonObject)?.array("logs") ?: return emptyList()
        return array.values.filterIsInstance<JsonObject>().mapNotNull { parseDaemonLog(it) }
    }

    fun parseDaemonLogTail(json: String): List<String> =
        (Json.parse(json) as? JsonObject)?.array("lines")?.values.orEmpty()
            .filterIsInstance<JsonString>().map { it.value }

    fun parseBuilds(json: String): List<Build> {
        val array = (Json.parse(json) as? JsonObject)?.array("builds") ?: return emptyList()
        return array.values.filterIsInstance<JsonObject>().mapNotNull { parseBuild(it) }
    }

    fun parseHealth(json: String): GoCoreHealthSnapshot {
        val root = Json.parse(json) as JsonObject
        return GoCoreHealthSnapshot(
            databasePath = root.string("db_path")?.takeIf { it.isNotBlank() },
            retentionMs = root.long("retention_ms"),
            pid = root.long("pid"),
        )
    }

    private fun parseBuild(obj: JsonObject): Build? {
        val buildId = obj.string("build_id") ?: return null
        val daemonPid = obj.long("daemon_pid") ?: return null
        val startTimeMs = obj.long("start_time_ms") ?: return null
        val inferredSource = obj.string("inferred_source")
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            ?: Source.UNKNOWN
        val finalStatus = obj.string("final_status")
            ?.let { runCatching { FinalStatus.valueOf(it) }.getOrNull() }
            ?: return null
        val tool = obj.string("tool")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BuildTool.valueOf(it.uppercase()) }.getOrNull() }
            ?: BuildTool.GRADLE
        val bazelProfile = obj.obj("bazel_profile")
            ?.let { BazelBuildTelemetryParser.parseProfile(it.stringify()) }
        val bazelDetails = obj.obj("bazel_details")?.let { detailsObj ->
            val metrics = detailsObj.obj("metrics")?.stringify()
            BazelBuildTelemetryParser.parseDetails(
                bazelVersion = detailsObj.string("bazel_version"),
                sourceFormat = detailsObj.string("source_format"),
                collectionState = detailsObj.string("collection_state"),
                metricsJson = metrics,
            )
        }
        val windowSummaries = obj.array("window_summaries")
            ?.let { BazelBuildTelemetryParser.parseWindowSummaries(it.stringify()) }
            .orEmpty()
        return Build(
            buildId = buildId,
            daemonPid = daemonPid,
            daemonIdentity = obj.string("daemon_identity").nullIfEmpty(),
            commandLine = obj.string("command_line"),
            workingDirectory = obj.string("working_directory").nullIfEmpty(),
            projectPath = obj.string("project_path").nullIfEmpty(),
            startTimeMs = startTimeMs,
            endTimeMs = obj.long("end_time_ms"),
            durationSeconds = obj.number("duration_seconds"),
            peakMemoryMb = obj.long("peak_memory_mb"),
            avgMemoryMb = obj.long("avg_memory_mb"),
            peakCpuPercent = obj.number("peak_cpu_percent"),
            inferredSource = inferredSource,
            finalStatus = finalStatus,
            logSnippet = obj.string("log_snippet").nullIfEmpty(),
            agent = obj.string("agent").nullIfEmpty(),
            agentProvider = obj.string("agent_provider").nullIfEmpty(),
            tool = tool,
            bazelProfile = bazelProfile,
            bazelDetails = bazelDetails,
            windowSummaries = windowSummaries,
        )
    }

    private fun String?.nullIfEmpty(): String? = this?.takeIf { it.isNotEmpty() }

    private fun parseDaemonLog(obj: JsonObject): DaemonLog? {
        val pid = obj.long("pid") ?: return null
        val path = obj.string("path") ?: return null
        val gradleVersion = obj.string("gradle_version").orEmpty()
        val tool = obj.string("tool")?.takeIf { it.isNotBlank() }
        val source = obj.string("source")?.takeIf { it.isNotBlank() }
        return DaemonLog(
            pid = pid,
            gradleVersion = gradleVersion,
            path = Path.of(path),
            tool = tool,
            source = source,
        )
    }

    private fun parseProcess(obj: JsonObject): GradleProcess? {
        val pid = obj.long("pid") ?: return null
        val typeName = obj.string("type") ?: return null
        val type = runCatching { ProcessType.valueOf(typeName) }.getOrNull() ?: return null
        val commandLine = obj.string("command_line").orEmpty()
        val rss = obj.long("rss_memory_mb") ?: 0L
        val cpu = obj.number("cpu_percent")
        val parentPid = obj.long("parent_pid") ?: 0L
        val maxHeap = obj.long("max_heap_mb")
        val minHeap = obj.long("min_heap_mb")
        val gc = obj.string("gc")
        val startTimeMs = obj.long("start_time_ms") ?: 0L
        val status = obj.string("status") ?: "RUNNING"
        val automated = obj.boolean("automated") ?: false
        val workingDirectory = obj.string("working_directory")
        val projectPath = obj.string("project_path")
        return GradleProcess(
            pid = pid,
            parentPid = parentPid,
            ownerPid = obj.long("owner_pid"),
            ownerStartTimeMs = obj.long("owner_start_time_ms"),
            subtreeRssMemoryMb = obj.long("subtree_rss_memory_mb"),
            subtreeCpuPercent = obj.number("subtree_cpu_percent"),
            activeWorkerCount = obj.long("active_worker_count"),
            activeSandboxedActionCount = obj.long("active_sandboxed_action_count"),
            bazelOutputBase = obj.string("bazel_output_base")?.takeIf { it.isNotBlank() },
            bazelInstallBase = obj.string("bazel_install_base")?.takeIf { it.isNotBlank() },
            type = type,
            commandLine = commandLine,
            workingDirectory = workingDirectory,
            projectPath = projectPath,
            cpuPercent = cpu,
            cpuUserSecondsTotal = obj.number("cpu_user_seconds_total"),
            cpuSystemSecondsTotal = obj.number("cpu_system_seconds_total"),
            cpuTotalSecondsTotal = obj.number("cpu_total_seconds_total"),
            cpuCoresUsed = obj.number("cpu_cores_used"),
            logicalProcessorCount = obj.long("logical_processor_count"),
            resourceScope = obj.string("resource_scope")?.takeIf { it.isNotBlank() },
            rssMemoryMb = rss,
            maxHeapMb = maxHeap,
            minHeapMb = minHeap,
            gc = gc,
            startTimeMs = startTimeMs,
            status = status,
            automated = automated,
            liveHeap = parseLiveHeap(obj),
            virtualMemoryMb = obj.long("virtual_memory_mb"),
            swapMemoryMb = obj.long("swap_memory_mb"),
            proportionalBytes = obj.long("proportional_bytes"),
            privateCommittedBytes = obj.long("private_committed_bytes"),
            physicalFootprintBytes = obj.long("physical_footprint_bytes"),
            peakResidentBytes = obj.long("peak_resident_bytes"),
            threadCount = obj.long("thread_count"),
            readBytes = obj.long("read_bytes"),
            writeBytes = obj.long("write_bytes"),
            diskReadBytes = obj.long("disk_read_bytes"),
            diskWriteBytes = obj.long("disk_write_bytes"),
            readOperations = obj.long("read_operations"),
            writeOperations = obj.long("write_operations"),
            minorPageFaults = obj.long("minor_page_faults"),
            majorPageFaults = obj.long("major_page_faults"),
            voluntaryContextSwitches = obj.long("voluntary_context_switches"),
            involuntaryContextSwitches = obj.long("involuntary_context_switches"),
            openFileDescriptors = obj.long("open_file_descriptors"),
            metaspaceUsedMb = obj.long("metaspace_used_mb"),
            metaspaceCommittedMb = obj.long("metaspace_committed_mb"),
            youngGcCount = obj.long("young_gc_count"),
            youngGcTimeMs = obj.long("young_gc_time_ms"),
            youngGcTimeSeconds = obj.number("young_gc_time_seconds"),
            fullGcTimeSeconds = obj.number("full_gc_time_seconds"),
            concurrentGcTimeSeconds = obj.number("concurrent_gc_time_seconds"),
            totalGcTimeSeconds = obj.number("total_gc_time_seconds"),
            oldGcCount = obj.long("old_gc_count"),
            oldGcTimeMs = obj.long("old_gc_time_ms"),
            javaVersion = obj.string("java_version"),
            javaRuntimeVersion = obj.string("java_runtime_version"),
            javaVendor = obj.string("java_vendor"),
            javaVmName = obj.string("java_vm_name"),
            javaVmVersion = obj.string("java_vm_version"),
            osName = obj.string("os_name"),
            osArch = obj.string("os_arch"),
            activeProcessorCount = obj.long("active_processor_count"),
            memoryPools = parsePools(obj, "memory_pools"),
            codeCache = parsePools(obj, "code_cache"),
            classes = obj.obj("classes")?.let {
                JvmClassMetrics(
                    it.long("loaded"), it.long("unloaded_total"),
                    it.long("loaded_total"), it.number("time_seconds"),
                )
            },
            jit = obj.obj("jit")?.let {
                JvmJitMetrics(it.long("compiled_count"), it.long("failed_count"),
                    it.long("invalid_count"), it.number("compilation_time_seconds"))
            },
        )
    }

    private fun parsePools(obj: JsonObject, field: String): List<JvmMemoryPool> =
        obj.array(field)?.values.orEmpty().filterIsInstance<JsonObject>().map { pool ->
            fun value(name: String): JvmRawValue? = pool.obj(name)?.let {
                val number = it.number("value") ?: return@let null
                val unit = it.string("unit") ?: return@let null
                JvmRawValue(number, unit)
            }
            JvmMemoryPool(pool.string("name").orEmpty(), pool.string("source"),
                value("used"), value("committed"), value("max"), value("peak"))
        }

    /**
     * Maps Go `/v1/processes` heap_* fields onto [LiveJvmHeap].
     * Missing fields (older cored) → null; `heap_available: false` → unavailable (not zero).
     */
    private fun parseLiveHeap(obj: JsonObject): LiveJvmHeap? {
        val available = obj.boolean("heap_available")
        val sampledAt = obj.long("heap_sampled_at_ms")
        val used = obj.long("heap_used_mb")
        val committed = obj.long("heap_committed_mb")
        val max = obj.long("heap_max_mb")
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
