package io.github.cdsap.daemonitor.gradle

import java.lang.reflect.Method
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/** Optional bridge: loading this plugin must not require Develocity on the consumer classpath. */
internal object DevelocitySink {
    private const val CONFIGURATION = "com.gradle.develocity.agent.gradle.DevelocityConfiguration"
    private val logger = Logging.getLogger(DevelocitySink::class.java)

    fun register(project: Project, summaryProvider: () -> String?) {
        if (runCatching { Class.forName(CONFIGURATION, false, javaClass.classLoader) }.isFailure) return
        val extension = project.rootProject.extensions.findByName("develocity") ?: return
        val buildScan =
            runCatching { extension.javaClass.methods.first { it.name == "getBuildScan" }.invoke(extension) }
                .onFailure { logger.warn("Daemonitor: could not read develocity.buildScan; GBOS custom values skipped.", it) }
                .getOrNull() ?: return
        val callback = Action<Any> { publish(buildScan, summaryProvider()) }
        val buildFinished = findBuildFinished(buildScan.javaClass) ?: run {
            logger.warn("Daemonitor: no buildFinished(Action) on Develocity buildScan; GBOS custom values skipped.")
            return
        }
        runCatching { buildFinished.invoke(buildScan, callback) }
            .onFailure { logger.warn("Daemonitor: failed to register buildFinished callback; GBOS custom values skipped.", it) }
    }

    private fun publish(buildScan: Any, summaryJson: String?) {
        if (summaryJson.isNullOrBlank()) return
        val values = GbosDevelocityProjection.values(ObservationSummaryParser.parse(summaryJson))
        val valueMethod = findValueMethod(buildScan.javaClass) ?: run {
            logger.warn("Daemonitor: no value(String, String) on Develocity buildScan; GBOS custom values skipped.")
            return
        }
        values.forEach { (key, value) ->
            runCatching { valueMethod.invoke(buildScan, key, value) }
                .onFailure { logger.warn("Daemonitor: failed to set Develocity custom value $key.", it) }
        }
    }

    /** Prefer Action over Groovy Closure — both are one-arg on decorated buildScan types. */
    internal fun findBuildFinished(type: Class<*>): Method? =
        type.methods.firstOrNull {
            it.name == "buildFinished" &&
                it.parameterCount == 1 &&
                Action::class.java.isAssignableFrom(it.parameterTypes[0])
        }

    internal fun findValueMethod(type: Class<*>): Method? =
        type.methods.firstOrNull {
            it.name == "value" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }
}
