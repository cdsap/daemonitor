package io.github.cdsap.daemonitor.persistence

/** Port for loading and saving user-configurable application settings. */
interface SettingsRepository {
    fun load(): Settings
    fun save(settings: Settings)
}
