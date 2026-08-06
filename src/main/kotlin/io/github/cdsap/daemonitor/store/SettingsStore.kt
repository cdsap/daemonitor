package io.github.cdsap.daemonitor.store

import io.github.cdsap.daemonitor.Defaults
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/** User-configurable settings (KTD-9 deferred config, now partially realized). */
data class Settings(
    val retentionDays: Long = Defaults.DEFAULT_RETENTION_DAYS,
    val appearance: AppearancePreference = AppearancePreference.SYSTEM,
    val mcpEnabled: Boolean = false,
    val mcpPort: Int = Defaults.DEFAULT_MCP_PORT,
    val mcpToken: String = "",
)

enum class AppearancePreference { SYSTEM, LIGHT, DARK }

/**
 * Tiny properties-file settings store kept alongside the database. A full config table would be
 * over-built for a single scalar; a properties file is dependency-free, human-readable, and avoids
 * a schema migration. Reads are defensive — a missing/corrupt file or out-of-range value falls back
 * to the default rather than failing the app.
 */
class SettingsStore(private val path: Path = Defaults.SETTINGS_PATH) {

    fun load(): Settings {
        if (!path.exists()) return Settings(mcpToken = newMcpToken())
        val props = Properties()
        runCatching { path.inputStream().use { props.load(it) } }
        val retention = props.getProperty(KEY_RETENTION)?.toLongOrNull()
            ?.coerceIn(Defaults.MIN_RETENTION_DAYS, Defaults.MAX_RETENTION_DAYS)
            ?: Defaults.DEFAULT_RETENTION_DAYS
        val appearance = props.getProperty(KEY_APPEARANCE)
            ?.let { stored -> AppearancePreference.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } }
            ?: AppearancePreference.SYSTEM
        val mcpPort = props.getProperty(KEY_MCP_PORT)?.toIntOrNull()?.takeIf { it in 1..65_535 }
            ?: Defaults.DEFAULT_MCP_PORT
        val mcpToken = props.getProperty(KEY_MCP_TOKEN)?.takeIf { it.isNotBlank() } ?: newMcpToken()
        return Settings(
            retentionDays = retention,
            appearance = appearance,
            mcpEnabled = props.getProperty(KEY_MCP_ENABLED).toBoolean(),
            mcpPort = mcpPort,
            mcpToken = mcpToken,
        )
    }

    fun save(settings: Settings) {
        runCatching {
            Files.createDirectories(path.parent)
            val mcpToken = settings.mcpToken.ifBlank { newMcpToken() }
            val props = Properties().apply {
                setProperty(KEY_RETENTION, settings.retentionDays.toString())
                setProperty(KEY_APPEARANCE, settings.appearance.name.lowercase())
                setProperty(KEY_MCP_ENABLED, settings.mcpEnabled.toString())
                setProperty(KEY_MCP_PORT, settings.mcpPort.toString())
                setProperty(KEY_MCP_TOKEN, mcpToken)
            }
            path.outputStream().use { props.store(it, "Daemonitor settings") }
        }
    }

    private companion object {
        const val KEY_RETENTION = "retentionDays"
        const val KEY_APPEARANCE = "appearance"
        const val KEY_MCP_ENABLED = "mcpEnabled"
        const val KEY_MCP_PORT = "mcpPort"
        const val KEY_MCP_TOKEN = "mcpToken"
    }
}

private fun newMcpToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
