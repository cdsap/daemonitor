package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.config.RetentionPolicy

/** Port for purging persisted samples and builds outside the retention window. */
interface RetentionRepository {
    fun purgeOlderThan(
        nowMs: Long,
        retentionDays: Long = RetentionPolicy.DEFAULT.defaultDays,
    )
}
