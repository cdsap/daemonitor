package io.github.cdsap.daemonitor.infrastructure.platform

import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import kotlin.system.exitProcess

/** Terminates the JVM so a staged desktop update helper can replace the install. */
class SystemProcessExiter(
    private val status: Int = 0,
) : ProcessExiter {
    override fun exit() {
        exitProcess(status)
    }
}
