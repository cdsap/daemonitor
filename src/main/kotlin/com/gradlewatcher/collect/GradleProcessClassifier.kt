package com.gradlewatcher.collect

import com.gradlewatcher.domain.model.ProcessType

/**
 * Classifies a process command line into a [ProcessType], or `null` when it is not Gradle-related
 * (U2). Order matters: more specific daemon/worker markers are checked before the generic
 * java-with-gradle fallback.
 */
object GradleProcessClassifier {

    fun classify(commandLine: String): ProcessType? {
        val cl = commandLine
        return when {
            cl.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon") ||
                cl.contains("GradleDaemon") -> ProcessType.GRADLE_DAEMON

            cl.contains("org.jetbrains.kotlin.daemon") ||
                cl.contains("KotlinCompileDaemon") -> ProcessType.KOTLIN_DAEMON

            cl.contains("worker.org.gradle.process.internal.worker.GradleWorkerMain") ||
                cl.contains("org.gradle.process.internal.worker.GradleWorkerMain") -> ProcessType.TEST_WORKER

            cl.contains("org.gradle.wrapper.GradleWrapperMain") ||
                containsGradlewInvocation(cl) -> ProcessType.GRADLE_WRAPPER

            isJava(cl) && hasGradleRuntimeMarker(cl) -> ProcessType.JAVA_GRADLE_RELATED

            else -> null
        }
    }

    private fun containsGradlewInvocation(cl: String): Boolean =
        Regex("""(^|[\s/])gradlew(\s|$)""").containsMatchIn(cl)

    private fun isJava(cl: String): Boolean =
        Regex("""(^|[\s/])java(\s|$)""").containsMatchIn(cl)

    // A genuine Gradle *runtime* signal — a Gradle system property, a Gradle launcher/worker/tooling
    // class, or the worker main. Deliberately does NOT match a bare "~/.gradle/caches/…" classpath
    // path: every JVM whose dependencies were fetched by Gradle has that, which over-detected any
    // Gradle-built app (including this watcher itself).
    private val GRADLE_RUNTIME_MARKER = Regex(
        """(-Dorg\.gradle\.|org\.gradle\.(launcher|process|tooling|internal|api|workers)\.|GradleWorkerMain)""",
    )

    private fun hasGradleRuntimeMarker(cl: String): Boolean = GRADLE_RUNTIME_MARKER.containsMatchIn(cl)
}
