package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Application port for the current set of Gradle-related processes. */
fun interface ProcessSource {
    fun currentProcesses(): List<GradleProcess>
}
