package io.github.cdsap.daemonitor.mcp

import io.github.cdsap.daemonitor.BuildInfo
import io.github.cdsap.daemonitor.application.DaemonitorQueryService
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.store.ProcessSample
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class DaemonitorMcpServer(
    private val queries: DaemonitorQueryService,
) {
    internal fun handle(request: JsonObject): JsonObject? {
        val id = request.values["id"]
        val method = request.string("method")
        val params = request.obj("params") ?: JsonObject(emptyMap())

        if (id == null && method == "notifications/initialized") return null

        val result = runCatching {
            when (method) {
                "initialize" -> initializeResult(params)
                "server/discover" -> discoverResult()
                "tools/list" -> toolsListResult()
                "tools/call" -> callTool(params)
                "ping" -> JsonObject(emptyMap())
                else -> throw McpError(-32601, "Unknown MCP method: $method")
            }
        }

        return result.fold(
            onSuccess = { value ->
                jsonObject(
                    "jsonrpc" to JsonString("2.0"),
                    "id" to id,
                    "result" to value,
                )
            },
            onFailure = { error ->
                val mcpError = error as? McpError ?: McpError(-32603, error.message ?: "Internal error")
                jsonObject(
                    "jsonrpc" to JsonString("2.0"),
                    "id" to id,
                    "error" to jsonObject(
                        "code" to JsonNumber(mcpError.code),
                        "message" to JsonString(mcpError.publicMessage),
                    ),
                )
            },
        )
    }

    private fun initializeResult(params: JsonObject): JsonObject =
        jsonObject(
            "protocolVersion" to JsonString(params.string("protocolVersion") ?: LATEST_PROTOCOL_VERSION),
            "capabilities" to serverCapabilities(),
            "serverInfo" to serverInfo(),
            "instructions" to JsonString(
                "Read-only access to Daemonitor's retained SQL build history and current Gradle-related processes.",
            ),
        )

    private fun discoverResult(): JsonObject =
        jsonObject(
            "protocolVersion" to JsonString(LATEST_PROTOCOL_VERSION),
            "capabilities" to serverCapabilities(),
            "serverInfo" to serverInfo(),
            "tools" to tools(),
        )

    private fun serverCapabilities(): JsonObject =
        jsonObject("tools" to jsonObject("listChanged" to JsonBoolean(false)))

    private fun serverInfo(): JsonObject =
        jsonObject(
            "name" to JsonString("daemonitor"),
            "version" to JsonString(BuildInfo.current.version),
        )

    private fun toolsListResult(): JsonObject = jsonObject("tools" to tools())

    private fun tools(): JsonArray = jsonArray(
        tool(
            name = "daemonitor_builds_for_process",
            description = "Find retained builds executed by a Gradle daemon PID or by process text.",
            properties = jsonObject(
                "process" to jsonObject(
                    "type" to JsonString("string"),
                    "description" to JsonString(
                        "Daemon PID, daemon identity, project path, working directory, or command-line text.",
                    ),
                ),
                "limit" to limitSchema(),
            ),
            required = jsonArray(JsonString("process")),
        ),
        tool(
            name = "daemonitor_search_history",
            description = "Search retained build history by build id, command, project, status, source, or agent.",
            properties = jsonObject(
                "query" to jsonObject(
                    "type" to JsonString("string"),
                    "description" to JsonString("Search term. Empty returns the most recent retained builds."),
                ),
                "limit" to limitSchema(),
            ),
            required = JsonArray(emptyList()),
        ),
        tool(
            name = "daemonitor_current_processes",
            description = "Return the current Gradle-related processes visible to Daemonitor.",
            properties = jsonObject(),
            required = JsonArray(emptyList()),
        ),
    )

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: JsonArray,
    ): JsonObject = jsonObject(
        "name" to JsonString(name),
        "description" to JsonString(description),
        "inputSchema" to jsonObject(
            "type" to JsonString("object"),
            "properties" to properties,
            "required" to required,
            "additionalProperties" to JsonBoolean(false),
        ),
        "annotations" to jsonObject(
            "readOnlyHint" to JsonBoolean(true),
            "destructiveHint" to JsonBoolean(false),
        ),
    )

    private fun limitSchema(): JsonObject = jsonObject(
        "type" to JsonString("integer"),
        "description" to JsonString("Maximum number of rows to return, capped at 200."),
        "minimum" to JsonNumber(1),
        "maximum" to JsonNumber(200),
    )

    private fun callTool(params: JsonObject): JsonObject {
        val name = params.string("name") ?: throw McpError(-32602, "Missing tool name")
        val arguments = params.obj("arguments") ?: JsonObject(emptyMap())
        val payload = when (name) {
            "daemonitor_builds_for_process" -> buildsForProcess(arguments)
            "daemonitor_search_history" -> searchHistory(arguments)
            "daemonitor_current_processes" -> currentProcesses()
            else -> throw McpError(-32602, "Unknown Daemonitor tool: $name")
        }
        return toolText(payload.stringify())
    }

    private fun buildsForProcess(arguments: JsonObject): JsonObject {
        val process = arguments.string("process")?.trim()
            ?: throw McpError(-32602, "Missing required argument: process")
        if (process.isEmpty()) throw McpError(-32602, "Process argument cannot be empty")

        val result = queries.buildsForProcess(process, arguments.long("limit").coerceLimit())
        return jsonObject(
            "process" to JsonString(result.process),
            "matchedBuilds" to JsonArray(result.matchedBuilds.map { it.toJson() }),
            "matchedProcessSamples" to JsonArray(result.matchedProcessSamples.map { it.toJson() }),
        )
    }

    private fun searchHistory(arguments: JsonObject): JsonObject {
        val query = arguments.string("query").orEmpty()
        val builds = queries.searchHistory(query, arguments.long("limit").coerceLimit())
        return jsonObject(
            "query" to JsonString(query),
            "builds" to JsonArray(builds.map { it.toJson() }),
        )
    }

    private fun currentProcesses(): JsonObject {
        val processes = queries.currentProcesses()
        return jsonObject(
            "processes" to JsonArray(processes.map { it.toJson() }),
        )
    }

    private fun toolText(text: String): JsonObject =
        jsonObject(
            "content" to jsonArray(
                jsonObject(
                    "type" to JsonString("text"),
                    "text" to JsonString(text),
                ),
            ),
            "isError" to JsonBoolean(false),
        )

    private fun Long?.coerceLimit(): Int = (this ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT).toInt()

    private fun Build.toJson(): JsonObject = jsonObject(
        "buildId" to JsonString(buildId),
        "daemonPid" to JsonNumber(daemonPid),
        "daemonIdentity" to jsonStringOrNull(daemonIdentity),
        "commandLine" to jsonStringOrNull(commandLine),
        "workingDirectory" to jsonStringOrNull(workingDirectory),
        "projectPath" to jsonStringOrNull(projectPath),
        "startTimeMs" to JsonNumber(startTimeMs),
        "endTimeMs" to jsonNumberOrNull(endTimeMs),
        "durationSeconds" to jsonNumberOrNull(durationSeconds),
        "peakMemoryMb" to jsonNumberOrNull(peakMemoryMb),
        "avgMemoryMb" to jsonNumberOrNull(avgMemoryMb),
        "peakCpuPercent" to jsonNumberOrNull(peakCpuPercent),
        "inferredSource" to JsonString(inferredSource.name),
        "finalStatus" to JsonString(finalStatus.name),
        "logSnippet" to jsonStringOrNull(logSnippet),
        "agent" to jsonStringOrNull(agent),
        "agentProvider" to jsonStringOrNull(agentProvider),
    )

    private fun ProcessSample.toJson(): JsonObject = jsonObject(
        "timestampMs" to JsonNumber(timestampMs),
        "pid" to JsonNumber(pid),
        "parentPid" to JsonNumber(parentPid),
        "processType" to JsonString(processType.name),
        "commandLine" to JsonString(commandLine),
        "workingDirectory" to jsonStringOrNull(workingDirectory),
        "projectPath" to jsonStringOrNull(projectPath),
        "cpuPercent" to jsonNumberOrNull(cpuPercent),
        "rssMemoryMb" to JsonNumber(rssMemoryMb),
        "maxHeapMb" to jsonNumberOrNull(maxHeapMb),
        "status" to JsonString(status),
    )

    private fun GradleProcess.toJson(): JsonObject = jsonObject(
        "pid" to JsonNumber(pid),
        "parentPid" to JsonNumber(parentPid),
        "processType" to JsonString(type.name),
        "commandLine" to JsonString(commandLine),
        "workingDirectory" to jsonStringOrNull(workingDirectory),
        "projectPath" to jsonStringOrNull(projectPath),
        "cpuPercent" to jsonNumberOrNull(cpuPercent),
        "rssMemoryMb" to JsonNumber(rssMemoryMb),
        "maxHeapMb" to jsonNumberOrNull(maxHeapMb),
        "minHeapMb" to jsonNumberOrNull(minHeapMb),
        "gc" to jsonStringOrNull(gc),
        "startTimeMs" to JsonNumber(startTimeMs),
        "status" to JsonString(status),
        "automated" to JsonBoolean(automated),
    )

    private class McpError(val code: Int, val publicMessage: String) : RuntimeException(publicMessage)

    companion object {
        private const val LATEST_PROTOCOL_VERSION = "2026-07-28"
        private const val DEFAULT_LIMIT = 50L
        private const val MAX_LIMIT = 200L
    }
}

class McpMessageStream(
    input: InputStream,
    output: OutputStream,
) {
    private val input = BufferedInputStream(input)
    private val output = BufferedOutputStream(output)

    fun serve(server: DaemonitorMcpServer) {
        while (true) {
            val message = readMessage() ?: return
            val parsed = try {
                Json.parse(message)
            } catch (error: IllegalArgumentException) {
                writeMessage(responseError(null, -32700, "Parse error: ${error.message}"))
                continue
            } catch (error: IllegalStateException) {
                writeMessage(responseError(null, -32700, "Parse error: ${error.message}"))
                continue
            }
            val request = parsed as? JsonObject
            if (request == null) {
                writeMessage(responseError(null, -32600, "MCP request must be a JSON object"))
                continue
            }
            server.handle(request)?.let { writeMessage(it.stringify()) }
        }
    }

    private fun readMessage(): String? {
        val firstLine = readAsciiLine() ?: return null
        if (firstLine.startsWith("Content-Length:", ignoreCase = true)) {
            var contentLength = firstLine.substringAfter(':').trim().toInt()
            while (true) {
                val line = readAsciiLine() ?: return null
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toInt()
                }
            }
            return input.readNBytes(contentLength).toString(StandardCharsets.UTF_8)
        }
        return firstLine
    }

    private fun writeMessage(json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readAsciiLine(): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            if (next == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            bytes += next.toByte()
        }
    }

    private fun responseError(id: JsonValue?, code: Int, message: String): String =
        jsonObject(
            "jsonrpc" to JsonString("2.0"),
            "id" to id,
            "error" to jsonObject(
                "code" to JsonNumber(code),
                "message" to JsonString(message),
            ),
        ).stringify()
}
