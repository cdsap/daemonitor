package io.github.cdsap.daemonitor.domain.model

import io.github.cdsap.daemonitor.json.Json
import io.github.cdsap.daemonitor.json.JsonArray
import io.github.cdsap.daemonitor.json.JsonNumber
import io.github.cdsap.daemonitor.json.JsonObject

/** Parsed `builds.bazel_profile` JSON summary written by `cored`. */
data class BazelProfileSummary(
    val bazelVersion: String? = null,
    val date: String? = null,
    val phaseSeconds: Map<String, Double> = emptyMap(),
    val criticalPathSeconds: Double? = null,
    val criticalPathComponents: Int? = null,
    val peakCounters: Map<String, Double> = emptyMap(),
)

/** Parsed entry from `builds.window_summaries` JSON array written by `cored`. */
data class BazelWindowSummary(
    val pid: Long,
    val processType: String? = null,
    val sampleCount: Int = 0,
    val firstRssMb: Long = 0,
    val peakRssMb: Long = 0,
    val lastRssMb: Long = 0,
    val deltaRssMb: Long = 0,
    val firstHeapUsedMb: Long? = null,
    val peakHeapUsedMb: Long? = null,
    val lastHeapUsedMb: Long? = null,
    val deltaHeapUsedMb: Long? = null,
    val deltaTotalGcCount: Long? = null,
    val deltaYoungGcTimeMs: Long? = null,
    val deltaOldGcTimeMs: Long? = null,
)

/** Parsed projection of `bazel_build_details` (schema_version = 1) for UI display. */
data class BazelDetailsSummary(
    val bazelVersion: String? = null,
    val sourceFormat: String? = null,
    val collectionState: String? = null,
    val analysisPhaseMs: Long? = null,
    val executionPhaseMs: Long? = null,
    val wallTimeMs: Long? = null,
    val cpuTimeMs: Long? = null,
    val actionsCreated: Long? = null,
    val actionsExecuted: Long? = null,
    val remoteCacheHits: Long? = null,
    val localCacheHits: Long? = null,
    val localCacheMisses: Long? = null,
    val usedHeapPostBuildBytes: Long? = null,
    val peakPostGcHeapBytes: Long? = null,
    val peakPostGcTenuredBytes: Long? = null,
    val workerCount: Int? = null,
    val testsTotal: Int? = null,
    val testsPassed: Int? = null,
    val testsFailed: Int? = null,
    val testsFlaky: Int? = null,
    val testsCached: Int? = null,
    val abortReason: String? = null,
    val summary: String? = null,
) {
    val hasTiming: Boolean
        get() = analysisPhaseMs != null || executionPhaseMs != null || wallTimeMs != null || cpuTimeMs != null

    val hasActions: Boolean
        get() = actionsCreated != null || actionsExecuted != null || remoteCacheHits != null ||
            localCacheHits != null || localCacheMisses != null

    val hasMemory: Boolean
        get() = usedHeapPostBuildBytes != null || peakPostGcHeapBytes != null || peakPostGcTenuredBytes != null
}

/**
 * Dependency-free parser for the bounded allowlisted JSON columns produced by `cored`
 * (`builds.bazel_profile`, `builds.window_summaries`, `bazel_build_details.metrics_json`).
 */
object BazelBuildTelemetryParser {
    fun parseProfile(json: String?): BazelProfileSummary? {
        val raw = parseObject(json) ?: return null
        val version = raw.string("bazel_version")?.takeIf { it.isNotBlank() }
        val date = raw.string("date")?.takeIf { it.isNotBlank() }
        val phases = raw.obj("phase_seconds")?.let(::parseDoubleMap).orEmpty()
        val criticalSec = raw.number("critical_path_seconds")?.takeIf { it > 0.0 }
        val criticalComp = raw.long("critical_path_components")?.toInt()?.takeIf { it > 0 }
        val counters = raw.obj("peak_counters")?.let(::parseDoubleMap).orEmpty()
        if (version == null && date == null && phases.isEmpty() && criticalSec == null && counters.isEmpty()) {
            return null
        }
        return BazelProfileSummary(
            bazelVersion = version,
            date = date,
            phaseSeconds = phases,
            criticalPathSeconds = criticalSec,
            criticalPathComponents = criticalComp,
            peakCounters = counters,
        )
    }

    fun parseWindowSummaries(json: String?): List<BazelWindowSummary> {
        val raw = json?.let { runCatching { Json.parse(it) as? JsonArray }.getOrNull() } ?: return emptyList()
        return raw.values.filterIsInstance<JsonObject>().mapNotNull { obj ->
            val pid = obj.long("pid") ?: return@mapNotNull null
            BazelWindowSummary(
                pid = pid,
                processType = obj.string("process_type")?.takeIf { it.isNotBlank() },
                sampleCount = obj.long("sample_count")?.toInt() ?: 0,
                firstRssMb = obj.long("first_rss_mb") ?: 0L,
                peakRssMb = obj.long("peak_rss_mb") ?: 0L,
                lastRssMb = obj.long("last_rss_mb") ?: 0L,
                deltaRssMb = obj.long("delta_rss_mb") ?: 0L,
                firstHeapUsedMb = obj.long("first_heap_used_mb"),
                peakHeapUsedMb = obj.long("peak_heap_used_mb"),
                lastHeapUsedMb = obj.long("last_heap_used_mb"),
                deltaHeapUsedMb = obj.long("delta_heap_used_mb"),
                deltaTotalGcCount = obj.long("delta_total_gc_count"),
                deltaYoungGcTimeMs = obj.long("delta_young_gc_time_ms"),
                deltaOldGcTimeMs = obj.long("delta_old_gc_time_ms"),
            )
        }
    }

    fun parseDetails(
        bazelVersion: String?,
        sourceFormat: String?,
        collectionState: String?,
        metricsJson: String?,
    ): BazelDetailsSummary? {
        val raw = parseObject(metricsJson)
        val timing = raw?.obj("timing")
        val actions = raw?.obj("actions")
        val memory = raw?.obj("memory")
        val testTotals = raw?.obj("test_totals")
        val diagnostics = raw?.obj("diagnostics")
        val workers = raw?.array("workers")?.values?.filterIsInstance<JsonObject>()
        val details = BazelDetailsSummary(
            bazelVersion = bazelVersion?.takeIf { it.isNotBlank() },
            sourceFormat = sourceFormat?.takeIf { it.isNotBlank() },
            collectionState = collectionState?.takeIf { it.isNotBlank() },
            analysisPhaseMs = timing?.long("analysis_phase_ms"),
            executionPhaseMs = timing?.long("execution_phase_ms"),
            wallTimeMs = timing?.long("wall_ms"),
            cpuTimeMs = timing?.long("cpu_ms"),
            actionsCreated = actions?.long("created"),
            actionsExecuted = actions?.long("executed"),
            remoteCacheHits = actions?.long("remote_cache_hits"),
            localCacheHits = actions?.long("local_cache_hits"),
            localCacheMisses = actions?.long("local_cache_misses"),
            usedHeapPostBuildBytes = memory?.long("used_heap_post_build_bytes"),
            peakPostGcHeapBytes = memory?.long("peak_post_gc_heap_bytes"),
            peakPostGcTenuredBytes = memory?.long("peak_post_gc_tenured_bytes"),
            workerCount = workers?.size?.takeIf { it > 0 },
            testsTotal = testTotals?.let { it.long("total")?.toInt() }?.takeIf { it > 0 },
            testsPassed = testTotals?.let { it.long("passed")?.toInt() },
            testsFailed = testTotals?.let { it.long("failed")?.toInt() },
            testsFlaky = testTotals?.let { it.long("flaky")?.toInt() },
            testsCached = testTotals?.let { it.long("cached")?.toInt() },
            abortReason = diagnostics?.string("abort_reason")?.takeIf { it.isNotBlank() },
            summary = diagnostics?.string("summary")?.takeIf { it.isNotBlank() },
        )
        return details.takeIf {
            it.bazelVersion != null || it.hasTiming || it.hasActions || it.hasMemory ||
                it.workerCount != null || it.testsTotal != null || it.abortReason != null || it.summary != null
        }
    }

    private fun parseObject(json: String?): JsonObject? =
        json?.let { runCatching { Json.parse(it) as? JsonObject }.getOrNull() }

    private fun parseDoubleMap(obj: JsonObject): Map<String, Double> =
        obj.values.mapNotNull { (key, value) ->
            (value as? JsonNumber)?.value?.toDouble()?.let { key to it }
        }.toMap()
}
