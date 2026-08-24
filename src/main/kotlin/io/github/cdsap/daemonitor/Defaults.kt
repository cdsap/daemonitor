package io.github.cdsap.daemonitor

/**
 * Remaining hard-coded MVP policy that is neither monitoring cadence nor retention
 * (KTD-9). Poll/log limits live in [io.github.cdsap.daemonitor.config.MonitoringConfig];
 * retention in [io.github.cdsap.daemonitor.config.RetentionPolicy]; paths in
 * [io.github.cdsap.daemonitor.platform.AppDirectories].
 */
object Defaults {
    /** Default localhost port for the optional MCP HTTP endpoint exposed from Settings. */
    const val DEFAULT_MCP_PORT: Int = 17333

    /** Memory highlight thresholds, in MiB of RSS (U9). */
    const val MEM_WARN_MB: Long = 4_096
    const val MEM_CRIT_MB: Long = 8_192
}
