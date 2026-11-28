package io.github.cdsap.daemonitor.gradle

data class ObservationSummary(
    val name: String,
    val association: String,
    val hostId: String,
    val processes: List<ProcessRssSummary>,
    val buildsCount: Int,
    val buildsSuccessCount: Int,
    val buildsFailureCount: Int,
)

data class ProcessRssSummary(
    val hostId: String,
    val pid: Long,
    val startTimeMs: Long,
    val sampleCount: Int,
    val firstRssMb: Long,
    val peakRssMb: Long,
    val lastRssMb: Long,
    val deltaRssMb: Long,
    val association: String,
    val processType: String = "",
    val firstSampleAtMs: Long? = null,
    val lastSampleAtMs: Long? = null,
    val firstHeapUsedMb: Long? = null,
    val peakHeapUsedMb: Long? = null,
    val lastHeapUsedMb: Long? = null,
    val deltaHeapUsedMb: Long? = null,
    val firstMetaspaceUsedMb: Long? = null,
    val peakMetaspaceUsedMb: Long? = null,
    val lastMetaspaceUsedMb: Long? = null,
    val deltaMetaspaceUsedMb: Long? = null,
    val firstYoungGcCount: Long? = null,
    val lastYoungGcCount: Long? = null,
    val deltaYoungGcCount: Long? = null,
    val firstTotalGcCount: Long? = null,
    val lastTotalGcCount: Long? = null,
    val deltaTotalGcCount: Long? = null,
    val firstYoungGcTimeMs: Long? = null,
    val lastYoungGcTimeMs: Long? = null,
    val deltaYoungGcTimeMs: Long? = null,
    val firstOldGcTimeMs: Long? = null,
    val lastOldGcTimeMs: Long? = null,
    val deltaOldGcTimeMs: Long? = null,
)

internal object ObservationSummaryParser {
    fun parse(json: String): ObservationSummary {
        fun string(name: String, source: String = json): String =
            STRING_FIELDS.getValue(name)
                .find(source)?.groupValues?.get(1)?.replace("\\\\\\\"", "\\\"")
                ?: ""
        fun number(name: String, source: String = json): Long =
            NUMBER_FIELDS.getValue(name).find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        fun optionalNumber(name: String, source: String): Long? =
            NUMBER_FIELDS.getValue(name).find(source)?.groupValues?.get(1)?.toLongOrNull()
        val processBody = PROCESS_BODY
            .find(json)?.groupValues?.get(1).orEmpty()
        val processes = PROCESS_OBJECT.findAll(processBody).map { match ->
            val body = match.value
            ProcessRssSummary(
                hostId = string("host_id", body), pid = number("pid", body), startTimeMs = number("start_time_ms", body),
                sampleCount = number("sample_count", body).toInt(), firstRssMb = number("first_rss_mb", body),
                peakRssMb = number("peak_rss_mb", body), lastRssMb = number("last_rss_mb", body),
                deltaRssMb = number("delta_rss_mb", body), association = string("association", body),
                processType = string("process_type", body),
                firstSampleAtMs = optionalNumber("first_sample_at_ms", body),
                lastSampleAtMs = optionalNumber("last_sample_at_ms", body),
                firstHeapUsedMb = optionalNumber("first_heap_used_mb", body),
                peakHeapUsedMb = optionalNumber("peak_heap_used_mb", body),
                lastHeapUsedMb = optionalNumber("last_heap_used_mb", body),
                deltaHeapUsedMb = optionalNumber("delta_heap_used_mb", body),
                firstMetaspaceUsedMb = optionalNumber("first_metaspace_used_mb", body),
                peakMetaspaceUsedMb = optionalNumber("peak_metaspace_used_mb", body),
                lastMetaspaceUsedMb = optionalNumber("last_metaspace_used_mb", body),
                deltaMetaspaceUsedMb = optionalNumber("delta_metaspace_used_mb", body),
                firstYoungGcCount = optionalNumber("first_young_gc_count", body),
                lastYoungGcCount = optionalNumber("last_young_gc_count", body),
                deltaYoungGcCount = optionalNumber("delta_young_gc_count", body),
                firstTotalGcCount = optionalNumber("first_total_gc_count", body),
                lastTotalGcCount = optionalNumber("last_total_gc_count", body),
                deltaTotalGcCount = optionalNumber("delta_total_gc_count", body),
                firstYoungGcTimeMs = optionalNumber("first_young_gc_time_ms", body),
                lastYoungGcTimeMs = optionalNumber("last_young_gc_time_ms", body),
                deltaYoungGcTimeMs = optionalNumber("delta_young_gc_time_ms", body),
                firstOldGcTimeMs = optionalNumber("first_old_gc_time_ms", body),
                lastOldGcTimeMs = optionalNumber("last_old_gc_time_ms", body),
                deltaOldGcTimeMs = optionalNumber("delta_old_gc_time_ms", body),
            )
        }.toList()
        val builds = BUILDS_BODY.find(json)?.value.orEmpty()
        return ObservationSummary(
            name = string("name"), association = string("association"), hostId = string("host_id"), processes = processes,
            buildsCount = number("count", builds).toInt(), buildsSuccessCount = number("success_count", builds).toInt(),
            buildsFailureCount = number("failure_count", builds).toInt(),
        )
    }
}

private val PROCESS_BODY = Regex("\\\"processes\\\"\\s*:\\s*\\[(.*)]\\s*,\\s*\\\"builds\\\"", RegexOption.DOT_MATCHES_ALL)

private val PROCESS_OBJECT = Regex("\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)

private val BUILDS_BODY = Regex("\\\"builds\\\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)

private val STRING_FIELDS = listOf(
    "association",
    "host_id",
    "name",
    "process_type",
).associateWith { name ->
    Regex("\\\"$name\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
}

private val NUMBER_FIELDS = listOf(
    "count",
    "delta_heap_used_mb",
    "delta_metaspace_used_mb",
    "delta_old_gc_time_ms",
    "delta_rss_mb",
    "delta_total_gc_count",
    "delta_young_gc_count",
    "delta_young_gc_time_ms",
    "failure_count",
    "first_heap_used_mb",
    "first_metaspace_used_mb",
    "first_old_gc_time_ms",
    "first_rss_mb",
    "first_sample_at_ms",
    "first_total_gc_count",
    "first_young_gc_count",
    "first_young_gc_time_ms",
    "last_heap_used_mb",
    "last_metaspace_used_mb",
    "last_old_gc_time_ms",
    "last_rss_mb",
    "last_sample_at_ms",
    "last_total_gc_count",
    "last_young_gc_count",
    "last_young_gc_time_ms",
    "peak_heap_used_mb",
    "peak_metaspace_used_mb",
    "peak_rss_mb",
    "pid",
    "sample_count",
    "start_time_ms",
    "success_count",
).associateWith { name ->
    Regex("\\\"$name\\\"\\s*:\\s*(-?\\d+)")
}
