package io.github.cdsap.daemonitor.gradle

import java.net.InetAddress
import java.nio.file.Path
import java.util.UUID
import org.gradle.api.Plugin
import org.gradle.api.Project

class DaemonitorGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val service = project.gradle.sharedServices.registerIfAbsent(
            SERVICE_NAME,
            DaemonitorObservationBuildService::class.java,
        ) { spec ->
            spec.parameters.enabled.convention(
                project.providers.gradleProperty("daemonitor.gbos.enabled")
                    .map { it.toBooleanStrictOrNull() ?: true }
                    .orElse(true),
            )
            spec.parameters.socketPath.convention(
                project.providers.gradleProperty("daemonitor.cored.socket")
                    .orElse(defaultSocketPath()),
            )
            spec.parameters.rootProjectPath.convention(project.rootProject.layout.projectDirectory.asFile.absolutePath)
        }

        // A shared service is started once, on first task execution, and closed by Gradle
        // after the build. Attaching it to every task keeps the registration configuration-cache safe.
        project.tasks.configureEach { task ->
            task.doFirst { service.get() }
        }
    }

    private fun defaultSocketPath(): String {
        val home = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
        return when {
            System.getProperty("os.name").contains("mac", ignoreCase = true) ->
                Path.of(home, "Library", "Application Support", "Daemonitor", "daemonitor-core.sock").toString()
            System.getProperty("os.name").contains("win", ignoreCase = true) -> "\\\\.\\pipe\\daemonitor-core"
            else -> Path.of(
                System.getenv("XDG_DATA_HOME") ?: Path.of(home, ".local", "share").toString(),
                "Daemonitor", "daemonitor-core.sock",
            ).toString()
        }
    }

    private companion object { const val SERVICE_NAME = "daemonitorObservation" }
}

abstract class DaemonitorObservationBuildService : org.gradle.api.services.BuildService<DaemonitorObservationBuildService.Parameters>, AutoCloseable {
    interface Parameters : org.gradle.api.services.BuildServiceParameters {
        val enabled: org.gradle.api.provider.Property<Boolean>
        val socketPath: org.gradle.api.provider.Property<String>
        val rootProjectPath: org.gradle.api.provider.Property<String>
    }

    private val sessionId = UUID.randomUUID().toString()
    private var openAttempted = false
    private var sessionOpened = false
    private var closed = false
    private var closeSnapshot: String? = null

    init {
        if (parameters.enabled.get()) openOnce()
    }

    @Synchronized
    private fun openOnce() {
        if (openAttempted) return
        openAttempted = true
        try {
            val response = client().open(openPayload())
            if (!response.isSuccess) throw ObservationClientException("cored open returned HTTP ${response.status}")
            sessionOpened = true
        } catch (failure: Throwable) {
            warn("observation skipped: daemonitor-cored could not be opened", failure)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (!sessionOpened || !parameters.enabled.get()) return
        try {
            val response = client().close(sessionId, closePayload())
            if (!response.isSuccess) throw ObservationClientException("cored close returned HTTP ${response.status}")
            closeSnapshot = response.body
            ObservationReportSink(Path.of(parameters.rootProjectPath.get())).writeIfPresent(closeSnapshot)
        } catch (failure: Throwable) {
            warn("observation close failed; build result was not changed", failure)
        }
    }

    internal fun memoizedCloseSnapshot(): String? = closeSnapshot

    private fun client() = UnixObservationSessionClient(Path.of(parameters.socketPath.get()))

    private fun openPayload(): String {
        val process = ProcessHandle.current()
        val start = process.info().startInstant().orElse(null)?.toEpochMilli()
        return jsonObject(
            "session_id" to sessionId,
            "root_project_path" to parameters.rootProjectPath.get(),
            "build_id" to sessionId,
            "host_id" to runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
            "gradle_jvm_pid" to process.pid().takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            "gradle_jvm_start_time_ms" to start,
            "started_at_ms" to System.currentTimeMillis(),
        )
    }

    private fun closePayload(): String = jsonObject(
        "ended_at_ms" to System.currentTimeMillis(),
        "outcome" to "success",
        "process_hints" to emptyList<Any>(),
    )

    private fun warn(message: String, failure: Throwable) {
        org.gradle.api.logging.Logging.getLogger(DaemonitorObservationBuildService::class.java)
            .warn("Daemonitor: $message. Continuing without observation.", failure)
    }

    companion object {
        internal fun jsonObject(vararg fields: Pair<String, Any?>): String = fields.joinToString(",", "{", "}") { (key, value) ->
            "\"${escape(key)}\":" + when (value) {
                null -> "null"
                is Number, is Boolean -> value.toString()
                is List<*> -> "[]"
                else -> "\"${escape(value.toString())}\""
            }
        }

        private fun escape(value: String): String = buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
