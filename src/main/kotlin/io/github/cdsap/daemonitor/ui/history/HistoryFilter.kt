package io.github.cdsap.daemonitor.ui.history

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.domain.model.Build

/** Rolling time ranges aligned with the history-retention choices in Settings. */
enum class TimeRange(val days: Long) {
    LAST_7_DAYS(7),
    LAST_15_DAYS(15),
    LAST_30_DAYS(30),
    LAST_60_DAYS(60),
    LAST_90_DAYS(90),
    ;

    val label: String
        get() = "$days days"

    fun cutoffMs(nowMs: Long): Long = nowMs - days * DAY_MS

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        init {
            check(entries.map(TimeRange::days) == Defaults.RETENTION_PRESETS) {
                "Historical ranges must match the configured retention presets"
            }
        }

        val DEFAULT: TimeRange = entries.single { it.days == Defaults.DEFAULT_RETENTION_DAYS }
    }
}

/** Active filter selection. Filters compound with AND (U8). */
data class HistoryFilter(
    val projectPath: String? = null,
    val timeRange: TimeRange = TimeRange.DEFAULT,
)

/** Pure filtering, kept separate from the ViewModel so it is deterministically testable (U8). */
object HistoryFilters {
    fun apply(builds: List<Build>, projectPath: String?, sinceMs: Long?): List<Build> =
        builds.filter { b ->
            (projectPath == null || b.projectPath == projectPath) &&
                (sinceMs == null || b.startTimeMs >= sinceMs)
        }
}
