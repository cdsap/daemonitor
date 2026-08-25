package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.application.DefaultDaemonitorQueryService
import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.mcp.DaemonitorMcpServer
import io.github.cdsap.daemonitor.mcp.McpMessageStream
import io.github.cdsap.daemonitor.store.WatcherDatabase
import java.io.InputStream
import java.io.OutputStream

/** Stdio composition root for `--mcp` mode. */
object DaemonitorMcpStdio {
    fun run(
        database: WatcherDatabase = WatcherDatabase.open(),
        processSource: ProcessSource = ProcessCollector(),
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
    ) {
        database.use {
            val server = DaemonitorMcpServer(DefaultDaemonitorQueryService(it, processSource))
            McpMessageStream(input, output).serve(server)
        }
    }
}
