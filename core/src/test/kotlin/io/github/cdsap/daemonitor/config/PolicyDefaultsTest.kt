package io.github.cdsap.daemonitor.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PolicyDefaultsTest {

    @Test
    fun `monitoring defaults match prior hard-coded values`() {
        val config = MonitoringConfig.DEFAULT
        assertEquals(2.seconds, config.pollInterval)
        assertEquals(100, config.logTailLines)
        assertEquals(100, config.logSnippetLimit.lines)
        assertEquals(16_000, config.logSnippetLimit.chars)
    }

    @Test
    fun `retention defaults match prior hard-coded values`() {
        val policy = RetentionPolicy.DEFAULT
        assertEquals(15, policy.defaultDays)
        assertEquals(1, policy.minDays)
        assertEquals(90, policy.maxDays)
        assertEquals(listOf(7L, 15L, 30L, 60L, 90L), policy.presets)
    }

    @Test
    fun `retention clamp respects min and max`() {
        val policy = RetentionPolicy.DEFAULT
        assertEquals(1, policy.clamp(0))
        assertEquals(90, policy.clamp(9_999))
        assertEquals(30, policy.clamp(30))
    }

    @Test
    fun `retention cutoff is now minus clamped days in millis`() {
        val policy = RetentionPolicy.DEFAULT
        val now = 100L * RetentionPolicy.MILLIS_PER_DAY
        assertEquals(now - 7 * RetentionPolicy.MILLIS_PER_DAY, policy.cutoffEpochMs(now, retentionDays = 7))
        assertEquals(now - policy.maxDays * RetentionPolicy.MILLIS_PER_DAY, policy.cutoffEpochMs(now, retentionDays = 9_999))
    }
}
