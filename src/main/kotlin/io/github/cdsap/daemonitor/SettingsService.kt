package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.persistence.Settings
import io.github.cdsap.daemonitor.persistence.SettingsRepository

/** Application service for loading and persisting user settings, including retention purges. */
class SettingsService(
    private val settings: SettingsRepository,
    private val retention: RetentionRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var current: Settings = settings.load()

    fun load(): Settings = current

    fun updateRetention(days: Long): Settings {
        val clamped = RetentionPolicy.DEFAULT.clamp(days)
        current = current.copy(retentionDays = clamped)
        settings.save(current)
        purgeNow()
        return current
    }

    fun updateAppearance(appearance: AppearancePreference): Settings {
        current = current.copy(appearance = appearance)
        settings.save(current)
        return current
    }

    fun updateMcpEnabled(enabled: Boolean): Settings {
        current = current.copy(mcpEnabled = enabled)
        settings.save(current)
        return current
    }

    /** Purge rows older than the currently configured retention window. */
    fun purgeNow() {
        retention.purgeOlderThan(clock(), current.retentionDays)
    }
}
