package io.github.cdsap.daemonitor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

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
     * PIDs belonging to a project that genuinely has multiple concurrent builds.
     *
     * A single build fans out into several processes sharing one working directory (a `gradlew`
     * wrapper, the daemon, test workers, the launcher JVM) — so counting raw processes per cwd
     * over-reports massively (it tags every process of every multi-process build). Instead we count
     * *build entry points*: a project has concurrent builds only when 2+ Gradle **wrapper**
     * invocations target it (each build invocation is one wrapper). Live-only: concurrency is a
     * point-in-time property, so this applies to the Live Monitor, not the Historical table.
     */
    fun concurrentSameProjectPids(processes: List<GradleProcess>): Set<Long> {
        val multiBuildProjects = processes
            .filter { it.type == ProcessType.GRADLE_WRAPPER && it.projectPath != null }
            .groupBy { it.projectPath }
            .filterValues { it.size > 1 }
            .keys
        if (multiBuildProjects.isEmpty()) return emptySet()
        return processes
            .filter { it.projectPath != null && it.projectPath in multiBuildProjects }
            .map { it.pid }
            .toSet()
    }
}

@Composable
fun MemoryBadge(level: BadgeLevel, modifier: Modifier = Modifier) {
    val (fg, bg, label) = when (level) {
        BadgeLevel.WARN -> Triple(LocalAccentColors.current.warn, LocalAccentColors.current.warnBg, "HIGH MEM")
        BadgeLevel.CRITICAL -> Triple(LocalAccentColors.current.danger, LocalAccentColors.current.dangerBg, "CRIT MEM")
    }
    Pill(label, fg, bg, modifier)
}

@Composable
fun ConcurrentBadge(modifier: Modifier = Modifier) {
    Pill("MULTI-BUILD", LocalAccentColors.current.info, LocalAccentColors.current.infoBg, modifier)
}

/** Marks an invocation run with `--non-interactive` / `--console=plain` (likely CI/script/agent). */
@Composable
fun AutomatedBadge(modifier: Modifier = Modifier) {
    Pill("AUTOMATED", LocalAccentColors.current.brand, LocalAccentColors.current.brandBg, modifier)
}
