package io.github.cdsap.daemonitor.domain.model

import java.util.concurrent.ConcurrentHashMap

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
        val raw = json?.trim()?.takeIf { it.startsWith("{") && it.endsWith("}") } ?: return null
        val version = stringField(raw, "bazel_version")?.takeIf { it.isNotBlank() }
        val date = stringField(raw, "date")?.takeIf { it.isNotBlank() }
        val phases = extractContainer(raw, "phase_seconds", '{', '}')?.let(::parseDoubleMap).orEmpty()
        val criticalSec = numberField(raw, "critical_path_seconds")?.takeIf { it > 0.0 }
        val criticalComp = longField(raw, "critical_path_components")?.toInt()?.takeIf { it > 0 }
        val counters = extractContainer(raw, "peak_counters", '{', '}')?.let(::parseDoubleMap).orEmpty()
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
        val raw = json?.trim()?.takeIf { it.startsWith("[") && it.endsWith("]") } ?: return emptyList()
        val inner = raw.substring(1, raw.length - 1)
        return splitObjects(inner).mapNotNull { obj ->
            val pid = longField(obj, "pid") ?: return@mapNotNull null
            BazelWindowSummary(
                pid = pid,
                processType = stringField(obj, "process_type")?.takeIf { it.isNotBlank() },
                sampleCount = longField(obj, "sample_count")?.toInt() ?: 0,
                firstRssMb = longField(obj, "first_rss_mb") ?: 0L,
                peakRssMb = longField(obj, "peak_rss_mb") ?: 0L,
                lastRssMb = longField(obj, "last_rss_mb") ?: 0L,
                deltaRssMb = longField(obj, "delta_rss_mb") ?: 0L,
                firstHeapUsedMb = longField(obj, "first_heap_used_mb"),
                peakHeapUsedMb = longField(obj, "peak_heap_used_mb"),
                lastHeapUsedMb = longField(obj, "last_heap_used_mb"),
                deltaHeapUsedMb = longField(obj, "delta_heap_used_mb"),
                deltaTotalGcCount = longField(obj, "delta_total_gc_count"),
                deltaYoungGcTimeMs = longField(obj, "delta_young_gc_time_ms"),
                deltaOldGcTimeMs = longField(obj, "delta_old_gc_time_ms"),
            )
        }
    }

    fun parseDetails(
        bazelVersion: String?,
        sourceFormat: String?,
        collectionState: String?,
        metricsJson: String?,
    ): BazelDetailsSummary? {
        val raw = metricsJson?.trim()?.takeIf { it.startsWith("{") && it.endsWith("}") }
        val timing = raw?.let { extractContainer(it, "timing", '{', '}') }
        val actions = raw?.let { extractContainer(it, "actions", '{', '}') }
        val memory = raw?.let { extractContainer(it, "memory", '{', '}') }
        val testTotals = raw?.let { extractContainer(it, "test_totals", '{', '}') }
        val diagnostics = raw?.let { extractContainer(it, "diagnostics", '{', '}') }
        val workers = raw?.let { extractContainer(it, "workers", '[', ']') }?.let(::splitObjects)
        val details = BazelDetailsSummary(
            bazelVersion = bazelVersion?.takeIf { it.isNotBlank() },
            sourceFormat = sourceFormat?.takeIf { it.isNotBlank() },
            collectionState = collectionState?.takeIf { it.isNotBlank() },
            analysisPhaseMs = timing?.let { longField(it, "analysis_phase_ms") },
            executionPhaseMs = timing?.let { longField(it, "execution_phase_ms") },
            wallTimeMs = timing?.let { longField(it, "wall_ms") },
            cpuTimeMs = timing?.let { longField(it, "cpu_ms") },
            actionsCreated = actions?.let { longField(it, "created") },
            actionsExecuted = actions?.let { longField(it, "executed") },
            remoteCacheHits = actions?.let { longField(it, "remote_cache_hits") },
            localCacheHits = actions?.let { longField(it, "local_cache_hits") },
            localCacheMisses = actions?.let { longField(it, "local_cache_misses") },
            usedHeapPostBuildBytes = memory?.let { longField(it, "used_heap_post_build_bytes") },
            peakPostGcHeapBytes = memory?.let { longField(it, "peak_post_gc_heap_bytes") },
            peakPostGcTenuredBytes = memory?.let { longField(it, "peak_post_gc_tenured_bytes") },
            workerCount = workers?.size?.takeIf { it > 0 },
            testsTotal = testTotals?.let { longField(it, "total")?.toInt() }?.takeIf { it > 0 },
            testsPassed = testTotals?.let { longField(it, "passed")?.toInt() },
            testsFailed = testTotals?.let { longField(it, "failed")?.toInt() },
            testsFlaky = testTotals?.let { longField(it, "flaky")?.toInt() },
            testsCached = testTotals?.let { longField(it, "cached")?.toInt() },
            abortReason = diagnostics?.let { stringField(it, "abort_reason") }?.takeIf { it.isNotBlank() },
            summary = diagnostics?.let { stringField(it, "summary") }?.takeIf { it.isNotBlank() },
        )
        return details.takeIf {
            it.bazelVersion != null || it.hasTiming || it.hasActions || it.hasMemory ||
                it.workerCount != null || it.testsTotal != null || it.abortReason != null || it.summary != null
        }
    }

    private val containerKeyRegexes = ConcurrentHashMap<String, Regex>()
    private val DOUBLE_MAP_PAIR = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)")

    private fun extractContainer(json: String, name: String, open: Char, close: Char): String? {
        val regex = containerKeyRegexes.computeIfAbsent(name) { Regex("\"${Regex.escape(it)}\"\\s*:\\s*") }
        val match = regex.find(json) ?: return null
        val start = match.range.last + 1
        if (start >= json.length || json[start] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                open -> depth++
                close -> if (--depth == 0) return json.substring(start + 1, i)
            }
        }
        return null
    }

    private fun splitObjects(arrayBody: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in arrayBody.indices) {
            if (inString) {
                if (escaped) escaped = false else if (arrayBody[i] == '\\') escaped = true else if (arrayBody[i] == '"') inString = false
                continue
            }
            when (arrayBody[i]) {
                '"' -> inString = true
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

    private fun parseDoubleMap(body: String): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        for (m in DOUBLE_MAP_PAIR.findAll(body)) {
            val k = m.groupValues[1]
            val v = m.groupValues[2].toDoubleOrNull() ?: continue
            out[k] = v
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
            if (obj.startsWith("null", i)) return null
            if (obj[i] != '"') return null
            return decodeJsonString(obj, i + 1)
        }
    }

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
            if (i >= obj.length || obj.startsWith("null", i)) return null
            val start = i
            if (obj[i] == '-') i++
            while (i < obj.length && (obj[i].isDigit() || obj[i] == '.' || obj[i] == 'e' || obj[i] == 'E' || (i > start && (obj[i] == '+' || obj[i] == '-')))) {
                i++
            }
            return obj.substring(start, i).toDoubleOrNull()
        }
    }

    private fun longField(obj: String, name: String): Long? = numberField(obj, name)?.toLong()
}
