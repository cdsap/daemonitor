package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.BuildSource
import io.github.cdsap.daemonitor.application.DaemonLogSource
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.platform.AppDirectories
import java.nio.file.Path

/** Result of wiring optional `--core-socket` sources into CoreContainer / AppContainer. */
data class GoCoreWiring(
    val processSource: ProcessSource? = null,
    val logSource: DaemonLogSource? = null,
    val buildSource: BuildSource? = null,
    val persistSamples: Boolean = true,
    /** Human-readable stderr/log line when Go core is active; null when JVM collector is used. */
    val banner: String? = null,
)

/**
 * Build Go-core IPC sources for [coreSocket]. When the core's `db_path` matches [databasePath],
 * sample/build persistence stays with cored (`persistSamples = false`, no HTTP build import).
 */
fun wireGoCore(
    coreSocket: Path?,
    databasePath: Path,
    defaultDatabase: Path = AppDirectories.system.databasePath,
): GoCoreWiring {
    if (coreSocket == null) return GoCoreWiring()
    val owns = coreOwnsDatabase(coreSocket, databasePath, defaultDatabase)
    return GoCoreWiring(
        processSource = GoCoreProcessSource(coreSocket),
        logSource = GoCoreDaemonLogSource(coreSocket),
        buildSource = if (owns) null else GoCoreBuildSource(coreSocket),
        persistSamples = !owns,
        banner = if (owns) {
            "Reading live processes/logs from Go core at $coreSocket " +
                "(shared DB $databasePath — core owns sample/build writes)"
        } else {
            "Reading processes, daemon logs, and builds from Go core at $coreSocket"
        },
    )
}
