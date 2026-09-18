package io.github.cdsap.daemonitor.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bounds for persisted build-log excerpts (lines and characters). */
data class LogSnippetLimit(
    val lines: Int,
    val chars: Int,
)

/**
 * Application monitoring policy — poll cadence and log limits.
 * Independent of filesystem / OS path discovery.
 */
data class MonitoringConfig(
    val pollInterval: Duration,
    val logTailLines: Int,
    val logSnippetLimit: LogSnippetLimit,
) {
    companion object {
        /** Hard-coded MVP defaults (KTD-9); values unchanged from the previous [io.github.cdsap.daemonitor.Defaults]. */
        val DEFAULT: MonitoringConfig = MonitoringConfig(
            pollInterval = 2.seconds,
            logTailLines = 100,
            logSnippetLimit = LogSnippetLimit(lines = 100, chars = 16_000),
        )
    }
}
