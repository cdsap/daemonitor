package com.gradlewatcher

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

    /** History retention window; rows older than this are purged on startup (KTD-5). */
    const val RETENTION_DAYS: Long = 7

    /** Memory highlight thresholds, in MiB of RSS (U9). */
    const val MEM_WARN_MB: Long = 4_096
    const val MEM_CRIT_MB: Long = 8_192

    /** Gradle user home; daemon logs live under `<gradleUserHome>/daemon/<version>/`. */
    val GRADLE_USER_HOME: Path =
        Path(System.getProperty("user.home"), ".gradle")

    /** Local application-support directory for the SQLite database. */
    val APP_SUPPORT_DIR: Path =
        Path(System.getProperty("user.home"), "Library", "Application Support", "GradleWatcher")

    val DATABASE_PATH: Path = APP_SUPPORT_DIR.resolve("watcher.db")
}
