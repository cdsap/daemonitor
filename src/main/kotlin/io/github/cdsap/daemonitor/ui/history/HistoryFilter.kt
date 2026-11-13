package io.github.cdsap.daemonitor.ui.history

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildTool

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
            check(entries.map(TimeRange::days) == RetentionPolicy.DEFAULT.presets) {
                "Historical ranges must match the configured retention presets"
            }
        }

        val DEFAULT: TimeRange = entries.single { it.days == RetentionPolicy.DEFAULT.defaultDays }
    }
}

/** Tool chip for unified History (All / Gradle / Bazel). */
enum class ToolFilter {
    ALL,
    GRADLE,
    BAZEL,
    ;

    val label: String
        get() = when (this) {
            ALL -> "All"
            GRADLE -> "Gradle"
            BAZEL -> "Bazel"
        }
}

/** Active filter selection. Filters compound with AND (U8). */
data class HistoryFilter(
    val projectPath: String? = null,
    val timeRange: TimeRange = TimeRange.DEFAULT,
    val tool: ToolFilter = ToolFilter.ALL,
)

/** Pure filtering, kept separate from the ViewModel so it is deterministically testable (U8). */
object HistoryFilters {
    fun apply(
        builds: List<Build>,
        projectPath: String?,
        sinceMs: Long?,
        tool: ToolFilter = ToolFilter.ALL,
    ): List<Build> =
        builds.filter { b ->
            (projectPath == null || b.projectPath == projectPath) &&
                (sinceMs == null || b.startTimeMs >= sinceMs) &&
                when (tool) {
                    ToolFilter.ALL -> true
                    ToolFilter.GRADLE -> b.tool == BuildTool.GRADLE
                    ToolFilter.BAZEL -> b.tool == BuildTool.BAZEL
                }
        }
}
