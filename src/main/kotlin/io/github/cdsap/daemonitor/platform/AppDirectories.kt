package io.github.cdsap.daemonitor.platform

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Platform application directories — database, settings, and Gradle user home.
 * Path discovery stays at the infrastructure/platform edge; application policy does not live here.
 */
data class AppDirectories(
    val databasePath: Path,
    val settingsPath: Path,
    val gradleUserHome: Path,
    val appSupportDir: Path,
) {
    /** Staging directory for downloaded / extracted update artifacts. */
    val updatesDirectory: Path get() = appSupportDir.resolve("updates")

    companion object {
        /** Process-wide directories discovered from the current environment. */
        val system: AppDirectories by lazy { discover() }

        /**
         * Resolve per-user application-data and Gradle paths from [home], [osName], and [getenv].
         * Injectable for tests; production callers use [system].
         *
         * Platform conventions (unchanged):
         *   - Windows: `%LOCALAPPDATA%\Daemonitor` (fallback `~/AppData/Local`)
         *   - macOS:   `~/Library/Application Support/Daemonitor`
         *   - Linux/other: `$XDG_DATA_HOME/Daemonitor` (fallback `~/.local/share`)
         */
        fun discover(
            home: String = System.getProperty("user.home"),
            osName: String = System.getProperty("os.name"),
            getenv: (String) -> String? = System::getenv,
        ): AppDirectories {
            val appSupportDir = resolveAppSupportDir(home, osName, getenv)
            return AppDirectories(
                databasePath = appSupportDir.resolve("watcher.db"),
                settingsPath = appSupportDir.resolve("settings.properties"),
                gradleUserHome = Path(home, ".gradle"),
                appSupportDir = appSupportDir,
            )
        }

        private fun resolveAppSupportDir(
            home: String,
            osName: String,
            getenv: (String) -> String?,
        ): Path {
            val normalized = osName.lowercase()
            return when {
                normalized.contains("win") -> {
                    val localAppData = getenv("LOCALAPPDATA")
                    if (!localAppData.isNullOrBlank()) Path(localAppData, "Daemonitor")
                    else Path(home, "AppData", "Local", "Daemonitor")
                }
                normalized.contains("mac") || normalized.contains("darwin") ->
                    Path(home, "Library", "Application Support", "Daemonitor")
                else -> {
                    val xdg = getenv("XDG_DATA_HOME")
                    if (!xdg.isNullOrBlank()) Path(xdg, "Daemonitor")
                    else Path(home, ".local", "share", "Daemonitor")
                }
            }
        }
    }
}
