package io.github.cdsap.daemonitor

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hard-coded MVP configuration (KTD-9). There is no configuration UI in v1; these constants
 * are the single source of truth for tunable behavior. The configuration surface is deferred.
 */
object Defaults {
    /** How often the process collector samples (KTD-2). */
    val POLL_INTERVAL: Duration = 2.seconds

    /** Number of daemon-log lines retained for the live tail. */
    const val LOG_TAIL_LINES: Int = 100

    /** Default history retention window, in days; user-configurable via Settings (KTD-5). Rows
     *  older than the configured window are purged on startup and whenever the setting changes. */
    const val DEFAULT_RETENTION_DAYS: Long = 15

    /** Allowed retention range offered in Settings. */
    const val MIN_RETENTION_DAYS: Long = 1
    const val MAX_RETENTION_DAYS: Long = 90

    /** Memory highlight thresholds, in MiB of RSS (U9). */
    const val MEM_WARN_MB: Long = 4_096
    const val MEM_CRIT_MB: Long = 8_192

    /** Gradle user home; daemon logs live under `<gradleUserHome>/daemon/<version>/`. */
    val GRADLE_USER_HOME: Path =
        Path(System.getProperty("user.home"), ".gradle")

    /**
     * Per-user application-data directory for the SQLite database and settings, following each
     * platform's convention so the app is portable:
     *   - Windows: `%LOCALAPPDATA%\Daemonitor` (fallback `~/AppData/Local`)
     *   - macOS:   `~/Library/Application Support/Daemonitor`
     *   - Linux/other: `$XDG_DATA_HOME/Daemonitor` (fallback `~/.local/share`)
     */
    val APP_SUPPORT_DIR: Path = run {
        val home = System.getProperty("user.home")
        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (!localAppData.isNullOrBlank()) Path(localAppData, "Daemonitor")
                else Path(home, "AppData", "Local", "Daemonitor")
            }
            osName.contains("mac") || osName.contains("darwin") ->
                Path(home, "Library", "Application Support", "Daemonitor")
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                if (!xdg.isNullOrBlank()) Path(xdg, "Daemonitor")
                else Path(home, ".local", "share", "Daemonitor")
            }
        }
    }

    val DATABASE_PATH: Path = APP_SUPPORT_DIR.resolve("watcher.db")

    /** Settings file (Java properties) alongside the database. */
    val SETTINGS_PATH: Path = APP_SUPPORT_DIR.resolve("settings.properties")
}
