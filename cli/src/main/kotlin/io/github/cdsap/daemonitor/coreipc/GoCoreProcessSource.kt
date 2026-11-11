package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.application.HostContextSource
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import java.nio.file.Path

/**
 * [ProcessSource] that reads `/v1/processes` from `daemonitor-cored`
 * over Go core IPC.
 *
 * Live JVM heap used/committed comes from cored's jcmd/jstat probe for Gradle/Kotlin
 * daemons (see `cored/internal/heap`). Wrappers and workers stay unavailable.
 */
class GoCoreProcessSource internal constructor(
    private val client: GoCoreClient,
) : ProcessSource, HostContextSource {
    constructor(socketPath: Path, fetch: (Path, String) -> String = ::unixHttpGet) : this(
        GoCoreClient(socketPath, fetch),
    )

    override fun host() = GoCoreSnapshotParser.parseHost(client.get("/v1/host"))

    override fun capabilities() = GoCoreSnapshotParser.parseCapabilities(client.get("/v1/capabilities"))

    override fun currentProcesses(): List<GradleProcess> {
        val body = client.get("/v1/processes")
        return GoCoreSnapshotParser.parseProcesses(body)
    }
}
