package com.gradlewatcher.ui.history

import com.gradlewatcher.domain.model.Build
import java.time.Instant
import java.time.ZoneId

/** Time-range presets for the Historical view (U8). MVP: Today / Last 24h / All. */
enum class TimeRange(val label: String) {
    ALL("All"),
    TODAY("Today"),
    LAST_24H("Last 24 hours");

    /** Inclusive lower bound in epoch-ms, or null for ALL. */
    fun cutoffMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (this) {
        ALL -> null
        LAST_24H -> nowMs - 24L * 60 * 60 * 1000
        TODAY -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** Active filter selection. Filters compound with AND (U8). */
data class HistoryFilter(
    val projectPath: String? = null,
    val timeRange: TimeRange = TimeRange.TODAY,
)

/** Pure filtering, kept separate from the ViewModel so it is deterministically testable (U8). */
object HistoryFilters {
    fun apply(builds: List<Build>, projectPath: String?, sinceMs: Long?): List<Build> =
        builds.filter { b ->
            (projectPath == null || b.projectPath == projectPath) &&
                (sinceMs == null || b.startTimeMs >= sinceMs)
        }
}
