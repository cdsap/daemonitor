package io.github.cdsap.daemonitor.store

import io.github.cdsap.daemonitor.Defaults
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/** User-configurable settings (KTD-9 deferred config, now partially realized). */
data class Settings(
    val retentionDays: Long = Defaults.DEFAULT_RETENTION_DAYS,
)

/**
 * Tiny properties-file settings store kept alongside the database. A full config table would be
 * over-built for a single scalar; a properties file is dependency-free, human-readable, and avoids
 * a schema migration. Reads are defensive — a missing/corrupt file or out-of-range value falls back
 * to the default rather than failing the app.
 */
class SettingsStore(private val path: Path = Defaults.SETTINGS_PATH) {

    fun load(): Settings {
        if (!path.exists()) return Settings()
        val props = Properties()
        runCatching { path.inputStream().use { props.load(it) } }
        val retention = props.getProperty(KEY_RETENTION)?.toLongOrNull()
            ?.coerceIn(Defaults.MIN_RETENTION_DAYS, Defaults.MAX_RETENTION_DAYS)
            ?: Defaults.DEFAULT_RETENTION_DAYS
        return Settings(retentionDays = retention)
    }

    fun save(settings: Settings) {
        runCatching {
            Files.createDirectories(path.parent)
            val props = Properties().apply { setProperty(KEY_RETENTION, settings.retentionDays.toString()) }
            path.outputStream().use { props.store(it, "Daemonitor settings") }
        }
    }

    private companion object {
        const val KEY_RETENTION = "retentionDays"
    }
}
