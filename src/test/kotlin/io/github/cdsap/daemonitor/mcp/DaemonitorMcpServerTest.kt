package io.github.cdsap.daemonitor.mcp

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.store.WatcherDatabase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonitorMcpServerTest {

    @Test
    fun `lists read-only daemonitor tools`(@TempDirArg tmp: Path) {
        val server = server(tmp)

        val result = server.request("tools/list").result()
        val tools = result.array("tools")!!.values.filterIsInstance<JsonObject>()

        assertEquals(
            listOf(
                "daemonitor_builds_for_process",
                "daemonitor_search_history",
                "daemonitor_current_processes",
            ),
            tools.map { it.string("name") },
        )
        assertTrue(tools.all { it.obj("annotations")?.values?.get("readOnlyHint") == JsonBoolean(true) })
    }

    @Test
    fun `search history returns retained builds from sql`(@TempDirArg tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.save(build("old", 1_000, project = "/repo/a", status = FinalStatus.SUCCESS))
        db.save(build("match", 2_000, project = "/repo/target", status = FinalStatus.FAILED))
        val server = DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() })

        val payload = server.callTool(
            "daemonitor_search_history",
            jsonObject("query" to JsonString("target")),
        )

        val builds = payload.array("builds")!!.values.filterIsInstance<JsonObject>()
        assertEquals(listOf("match"), builds.map { it.string("buildId") })
        assertEquals("FAILED", builds.single().string("finalStatus"))
    }

    @Test
    fun `builds for process matches daemon pid and samples`(@TempDirArg tmp: Path) {
        val db = WatcherDatabase.open(tmp.resolve("watcher.db"))
        db.save(build("b1", 3_000, pid = 42, project = "/repo/target"))
        db.save(
            GradleProcess(
                pid = 42,
                parentPid = 7,
                type = ProcessType.GRADLE_DAEMON,
                commandLine = "java GradleDaemon",
                workingDirectory = "/repo/target",
                projectPath = "/repo/target",
                cpuPercent = 12.0,
                rssMemoryMb = 512,
                maxHeapMb = 2048,
                minHeapMb = null,
                gc = "G1",
                startTimeMs = 1_000,
                status = "RUNNING",
            ),
            timestampMs = 3_100,
        )
        val server = DaemonitorMcpServer(db, db, currentProcessesProvider = { emptyList() })

        val payload = server.callTool(
            "daemonitor_builds_for_process",
            jsonObject("process" to JsonString("42")),
        )

        assertEquals(listOf("b1"), payload.array("matchedBuilds")!!.values.map { (it as JsonObject).string("buildId") })
        val samples = payload.array("matchedProcessSamples")!!.values.filterIsInstance<JsonObject>()
        assertEquals(listOf(42L), samples.map { it.long("pid") })
        assertEquals("GRADLE_DAEMON", samples.single().string("processType"))
    }

    @Test
    fun `current processes uses injected collector data`(@TempDirArg tmp: Path) {
        val server = server(
            tmp = tmp,
            currentProcesses = listOf(
                GradleProcess(
                    pid = 99,
                    parentPid = 1,
                    type = ProcessType.KOTLIN_DAEMON,
                    commandLine = "java KotlinCompileDaemon",
                    workingDirectory = "/repo",
                    projectPath = "/repo",
                    cpuPercent = null,
                    rssMemoryMb = 384,
                    maxHeapMb = null,
                    minHeapMb = null,
                    gc = null,
                    startTimeMs = 10,
                    status = "SLEEPING",
                    automated = false,
                ),
            ),
        )

        val payload = server.callTool("daemonitor_current_processes", jsonObject())

        val processes = payload.array("processes")!!.values.filterIsInstance<JsonObject>()
        assertEquals(99L, processes.single().long("pid"))
        assertEquals("KOTLIN_DAEMON", processes.single().string("processType"))
    }

    private fun server(
        tmp: Path,
        currentProcesses: List<GradleProcess> = emptyList(),
    ): DaemonitorMcpServer {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        return DaemonitorMcpServer(
            builds = database,
            processSamples = database,
            currentProcessesProvider = { currentProcesses },
        )
    }

    private fun DaemonitorMcpServer.request(method: String, params: JsonObject = jsonObject()): JsonObject =
        handle(
            jsonObject(
                "jsonrpc" to JsonString("2.0"),
                "id" to JsonNumber(1),
                "method" to JsonString(method),
                "params" to params,
            ),
        )!!

    private fun DaemonitorMcpServer.callTool(name: String, arguments: JsonObject): JsonObject {
        val response = request(
            "tools/call",
            jsonObject(
                "name" to JsonString(name),
                "arguments" to arguments,
            ),
        )
        val text = response.result()
            .array("content")!!
            .values
            .filterIsInstance<JsonObject>()
            .single()
            .string("text")!!
        return Json.parse(text) as JsonObject
    }

    private fun JsonObject.result(): JsonObject = obj("result")!!

    private fun build(
        id: String,
        startMs: Long,
        pid: Long = 1,
        project: String = "/repo",
        status: FinalStatus = FinalStatus.SUCCESS,
    ) = Build(
        buildId = id,
        daemonPid = pid,
        daemonIdentity = "uid-$pid",
        commandLine = "gradlew build",
        workingDirectory = project,
        projectPath = project,
        startTimeMs = startMs,
        endTimeMs = startMs + 1000,
        durationSeconds = 1.0,
        peakMemoryMb = 700,
        avgMemoryMb = 600,
        peakCpuPercent = 50.0,
        inferredSource = Source.TERMINAL,
        finalStatus = status,
        logSnippet = "BUILD ${if (status == FinalStatus.SUCCESS) "SUCCESSFUL" else "FAILED"}",
        agent = null,
        agentProvider = null,
    )
}

private typealias TempDirArg = org.junit.jupiter.api.io.TempDir
