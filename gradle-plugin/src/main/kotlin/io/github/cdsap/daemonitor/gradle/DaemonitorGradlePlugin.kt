package io.github.cdsap.daemonitor.gradle

import io.github.cdsap.daemonitor.json.JsonBoolean
import io.github.cdsap.daemonitor.json.JsonNull
import io.github.cdsap.daemonitor.json.JsonNumber
import io.github.cdsap.daemonitor.json.JsonObject
import io.github.cdsap.daemonitor.json.JsonString
import java.net.InetAddress
import java.nio.file.Path
import java.util.UUID
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider

/**
 * Settings plugin: apply once in `settings.gradle.kts` so the whole build shares one
 * observation session against daemonitor-cored.
 */
class DaemonitorGradlePlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val gradle = settings.gradle
        val service: Provider<DaemonitorObservationBuildService> =
            gradle.sharedServices.registerIfAbsent(SERVICE_NAME, DaemonitorObservationBuildService::class.java) { spec ->
                spec.parameters.enabled.convention(
                    settings.providers.gradleProperty("daemonitor.gbos.enabled")
                        .map { it.toBooleanStrictOrNull() ?: true }
                        .orElse(true),
                )
                spec.parameters.socketPath.convention(
                    settings.providers.gradleProperty("daemonitor.cored.socket").orElse(defaultSocketPath()),
                )
                spec.parameters.rootProjectPath.convention(settings.rootDir.absolutePath)
            }

        // Open as soon as projects are loaded so the observation window covers the build,
        // not only the brief gap between the first task action and close. Capture the
        // instance: Develocity buildFinished can run after Gradle has already closed shared
        // services, and a fresh service.get() would open/close an empty session B that
        // overwrites the real summary and publishes empty gbos observation values.
        var observation: DaemonitorObservationBuildService? = null
        gradle.projectsLoaded {
            observation = service.get()
        }

        // Keep the service alive for the whole execution graph.
        gradle.beforeProject { project ->
            project.tasks.configureEach { task ->
                task.usesService(service)
            }
        }

        gradle.projectsEvaluated {
            val session = observation ?: service.get()
            // Close the captured instance — never service.get() inside the callback.
            DevelocitySink.register(gradle.rootProject, ObservationClose.callback(session))
        }
    }

    private fun defaultSocketPath(): String {
        val home = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
        return when {
            System.getProperty("os.name").contains("mac", true) ->
                Path.of(home, "Library", "Application Support", "Daemonitor", "daemonitor-core.sock").toString()
            System.getProperty("os.name").contains("win", true) -> "\\\\.\\pipe\\daemonitor-core"
            else ->
                Path.of(
                    System.getenv("XDG_DATA_HOME") ?: Path.of(home, ".local", "share").toString(),
                    "Daemonitor",
                    "daemonitor-core.sock",
                ).toString()
        }
    }

    private companion object {
        const val SERVICE_NAME = "daemonitorObservation"
    }
}

abstract class DaemonitorObservationBuildService :
    org.gradle.api.services.BuildService<DaemonitorObservationBuildService.Parameters>,
    AutoCloseable {
    interface Parameters : org.gradle.api.services.BuildServiceParameters {
        val enabled: org.gradle.api.provider.Property<Boolean>
        val socketPath: org.gradle.api.provider.Property<String>
        val rootProjectPath: org.gradle.api.provider.Property<String>
    }

    private val sessionName = UUID.randomUUID().toString()
    private val lifecycle =
        ObservationSessionLifecycle(
            enabled = parameters.enabled.get(),
            open = {
                client().open(
                    jsonObject(
                        "name" to sessionName,
                        "association" to "confirmed",
                        "host_id" to runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
                    ),
                )
            },
            close = { client().close(sessionName, jsonObject("name" to sessionName)) },
            onSummary = { summary -> ObservationReportSink(Path.of(parameters.rootProjectPath.get())).writeIfPresent(summary) },
            warn = ::warn,
        ).also { it.openOnce() }

    fun closeNow(): String? = lifecycle.closeOnce()

    override fun close() {
        closeNow()
    }

    private fun client() = UnixObservationSessionClient(Path.of(parameters.socketPath.get()))

    private fun warn(message: String, failure: Throwable) =
        org.gradle.api.logging.Logging.getLogger(javaClass)
            .warn("Daemonitor: $message. Continuing without observation.", failure)

    companion object {
        internal fun jsonObject(vararg fields: Pair<String, Any?>): String =
            JsonObject(fields.associate { (key, value) ->
                key to when (value) {
                    null -> JsonNull
                    is Number -> JsonNumber(value)
                    is Boolean -> JsonBoolean(value)
                    else -> JsonString(value.toString())
                }
            }).stringify()
    }
}

/**
 * Supplies Develocity (or any late buildFinished hook) with a close that targets the
 * observation BuildService opened for this build — not a fresh Provider.get().
 */
internal object ObservationClose {
    fun callback(session: DaemonitorObservationBuildService): () -> String? = { session.closeNow() }

    /** Unit-testable form: same capture rule against [ObservationSessionLifecycle]. */
    fun callback(lifecycle: ObservationSessionLifecycle): () -> String? = { lifecycle.closeOnce() }
}

/** Owns the one-shot cored lifecycle and keeps failed sessions out of every success sink. */
internal class ObservationSessionLifecycle(
    private val enabled: Boolean,
    private val open: () -> HttpResult,
    private val close: () -> HttpResult,
    private val onSummary: (String) -> Unit,
    private val warn: (String, Throwable) -> Unit,
) {
    private var openAttempted = false
    private var sessionOpened = false
    private var closed = false
    private var closeSnapshot: String? = null

    @Synchronized
    fun openOnce() {
        if (!enabled || openAttempted) return
        openAttempted = true
        runCatching {
            val response = open()
            check(response.isSuccess) { "cored open returned HTTP ${response.status}" }
            sessionOpened = true
        }.onFailure { warn("observation skipped: daemonitor-cored could not be opened", it) }
    }

    @Synchronized
    fun closeOnce(): String? {
        if (closed) return closeSnapshot
        closed = true
        if (!sessionOpened) return null
        runCatching {
            val response = close()
            check(response.isSuccess) { "cored close returned HTTP ${response.status}" }
            closeSnapshot = response.body
            onSummary(closeSnapshot!!)
        }.onFailure { warn("observation close failed; build result was not changed", it) }
        return closeSnapshot
    }
}
