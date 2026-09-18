package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.config.RetentionPolicy

/** User-configurable settings (KTD-9 deferred config, now partially realized). */
data class Settings(
    val retentionDays: Long = RetentionPolicy.DEFAULT.defaultDays,
    val appearance: AppearancePreference = AppearancePreference.SYSTEM,
    val mcpEnabled: Boolean = false,
    val mcpPort: Int = Defaults.DEFAULT_MCP_PORT,
    val mcpToken: String = "",
)

enum class AppearancePreference { SYSTEM, LIGHT, DARK }
