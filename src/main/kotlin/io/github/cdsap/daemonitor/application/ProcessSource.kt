package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.GradleProcess

/** Port for observing the current set of Gradle-related processes. */
interface ProcessSource {
    fun currentProcesses(): List<GradleProcess>
}
