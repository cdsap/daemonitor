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
)

internal object ObservationSummaryParser {
    fun parse(json: String): ObservationSummary {
        fun string(name: String, source: String = json): String =
            Regex("\\\"$name\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
                .find(source)?.groupValues?.get(1)?.replace("\\\\\\\"", "\\\"")
                ?: ""
        fun number(name: String, source: String = json): Long =
            Regex("\\\"$name\\\"\\s*:\\s*(-?\\d+)").find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val processBody = Regex("\\\"processes\\\"\\s*:\\s*\\[(.*)]\\s*,\\s*\\\"builds\\\"", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1).orEmpty()
        val processes = Regex("\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).findAll(processBody).map { match ->
            val body = match.value
            ProcessRssSummary(
                hostId = string("host_id", body), pid = number("pid", body), startTimeMs = number("start_time_ms", body),
                sampleCount = number("sample_count", body).toInt(), firstRssMb = number("first_rss_mb", body),
                peakRssMb = number("peak_rss_mb", body), lastRssMb = number("last_rss_mb", body),
                deltaRssMb = number("delta_rss_mb", body), association = string("association", body),
            )
        }.toList()
        val builds = Regex("\\\"builds\\\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(json)?.value.orEmpty()
        return ObservationSummary(
            name = string("name"), association = string("association"), hostId = string("host_id"), processes = processes,
            buildsCount = number("count", builds).toInt(), buildsSuccessCount = number("success_count", builds).toInt(),
            buildsFailureCount = number("failure_count", builds).toInt(),
        )
    }
}
