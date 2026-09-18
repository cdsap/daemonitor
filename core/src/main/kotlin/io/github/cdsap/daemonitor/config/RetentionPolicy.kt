package io.github.cdsap.daemonitor.config

/**
 * History retention policy — default window, allowed range, and UI presets.
 * Independent of filesystem / OS path discovery.
 */
data class RetentionPolicy(
    val defaultDays: Long,
    val minDays: Long,
    val maxDays: Long,
    val presets: List<Long>,
) {
    fun clamp(days: Long): Long = days.coerceIn(minDays, maxDays)

    companion object {
        /** Hard-coded MVP defaults (KTD-5 / KTD-9); values unchanged from the previous Defaults. */
        val DEFAULT: RetentionPolicy = RetentionPolicy(
            defaultDays = 15,
            minDays = 1,
            maxDays = 90,
            presets = listOf(7, 15, 30, 60, 90),
        )
    }
}
