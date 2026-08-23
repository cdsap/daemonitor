package io.github.cdsap.daemonitor.application.platform

/** Ends the current process so a staged update can replace the installation. */
fun interface ProcessExiter {
    fun exit()
}
