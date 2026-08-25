package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.persistence.AppearancePreference
import io.github.cdsap.daemonitor.persistence.Settings
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase

/** Application service for loading and persisting user settings, including retention purges. */
class SettingsService(
    private val settingsStore: SettingsStore,
    private val database: WatcherDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var current: Settings = settingsStore.load()

    fun load(): Settings = current

    fun updateRetention(days: Long): Settings {
        val clamped = RetentionPolicy.DEFAULT.clamp(days)
        current = current.copy(retentionDays = clamped)
        settingsStore.save(current)
        purgeNow()
        return current
    }

    fun updateAppearance(appearance: AppearancePreference): Settings {
        current = current.copy(appearance = appearance)
        settingsStore.save(current)
        return current
    }

    fun updateMcpEnabled(enabled: Boolean): Settings {
        current = current.copy(mcpEnabled = enabled)
        settingsStore.save(current)
        return current
    }

    /** Purge rows older than the currently configured retention window. */
    fun purgeNow() {
        database.purgeOlderThan(clock(), current.retentionDays)
    }
}
