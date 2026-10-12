package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.BuildSource
import io.github.cdsap.daemonitor.domain.model.Build
import java.nio.file.Path

/**
 * Experimental [BuildSource] that reads `GET /v1/builds` from `daemonitor-cored`
 * over a Unix-domain socket (Go core IPC spike).
 *
 * When wired into [io.github.cdsap.daemonitor.application.PollMonitoring], JVM log
 * re-aggregation is skipped and confirmed builds are imported from the Go spike DB.
 */
class GoCoreBuildSource internal constructor(
    private val client: GoCoreClient,
) : BuildSource {
    constructor(socketPath: Path, fetch: (Path, String) -> String = ::unixHttpGet) : this(
        GoCoreClient(socketPath, fetch),
    )

    override fun recentBuilds(limit: Int): List<Build> {
        val capped = limit.coerceIn(1, 500)
        val body = client.get("/v1/builds?limit=$capped")
        return GoCoreSnapshotParser.parseBuilds(body)
    }
}
