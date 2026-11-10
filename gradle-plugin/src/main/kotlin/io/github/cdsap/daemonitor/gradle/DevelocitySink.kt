package io.github.cdsap.daemonitor.gradle

import java.lang.reflect.Method
import org.gradle.api.Action
import org.gradle.api.Project

/** Optional bridge: loading this plugin must not require Develocity on the consumer classpath. */
internal object DevelocitySink {
    private const val CONFIGURATION = "com.gradle.develocity.agent.gradle.DevelocityConfiguration"

    fun register(project: Project, summaryProvider: () -> String?) {
        if (runCatching { Class.forName(CONFIGURATION, false, javaClass.classLoader) }.isFailure) return
        val extension = project.rootProject.extensions.findByName("develocity") ?: return
        val buildScan = runCatching { extension.javaClass.methods.first { it.name == "getBuildScan" }.invoke(extension) }.getOrNull() ?: return
        val callback = Action<Any> { publish(buildScan, summaryProvider()) }
        runCatching { buildScan.javaClass.methods.first { it.name == "buildFinished" && it.parameterCount == 1 }.invoke(buildScan, callback) }
    }

    private fun publish(buildScan: Any, summaryJson: String?) {
        if (summaryJson.isNullOrBlank()) return
        val values = GbosDevelocityProjection.values(ObservationSummaryParser.parse(summaryJson))
        val valueMethod: Method = buildScan.javaClass.methods.firstOrNull { it.name == "value" && it.parameterCount == 2 } ?: return
        values.forEach { (key, value) -> runCatching { valueMethod.invoke(buildScan, key, value) } }
    }
}
