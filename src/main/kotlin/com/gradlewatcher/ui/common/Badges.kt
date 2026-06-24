package com.gradlewatcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gradlewatcher.Defaults
import com.gradlewatcher.domain.model.GradleProcess

/** Severity of a highlight badge (U9). */
enum class BadgeLevel { WARN, CRITICAL }

/**
 * Pure highlight rules (U9). Inlined here rather than in a dedicated domain abstraction — there
 * are only two hard-coded rules with no runtime configuration, so a `HighlightRules` class would
 * not earn its keep. Badges are derived state recomputed each poll, so they auto-clear (no sticky
 * notifications).
 */
object Badges {

    /** Memory badge for a process. Null RSS (sub-poll) cannot trigger a badge (KTD-2). */
    fun memoryBadge(rssMemoryMb: Long?): BadgeLevel? = when {
        rssMemoryMb == null -> null
        rssMemoryMb >= Defaults.MEM_CRIT_MB -> BadgeLevel.CRITICAL
        rssMemoryMb >= Defaults.MEM_WARN_MB -> BadgeLevel.WARN
        else -> null
    }

    /**
     * PIDs that share a project path with at least one other live process — "multiple concurrent
     * builds for the same project". Live-only: concurrency is a point-in-time property, so this
     * applies to the Live Monitor, not the Historical table.
     */
    fun concurrentSameProjectPids(processes: List<GradleProcess>): Set<Long> =
        processes
            .filter { it.projectPath != null }
            .groupBy { it.projectPath }
            .filterValues { it.size > 1 }
            .values.flatten()
            .map { it.pid }
            .toSet()
}

@Composable
fun MemoryBadge(level: BadgeLevel, modifier: Modifier = Modifier) {
    val (bg, label) = when (level) {
        BadgeLevel.WARN -> Color(0xFFB26A00) to "HIGH MEM"
        BadgeLevel.CRITICAL -> Color(0xFFB00020) to "CRIT MEM"
    }
    Text(
        text = label,
        color = Color.White,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun ConcurrentBadge(modifier: Modifier = Modifier) {
    Text(
        text = "MULTI-BUILD",
        color = Color.White,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF5E35B1))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Marks an invocation run with `--non-interactive` / `--console=plain` (likely CI/script/agent). */
@Composable
fun AutomatedBadge(modifier: Modifier = Modifier) {
    Text(
        text = "AUTOMATED",
        color = Color.White,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF00796B))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}
