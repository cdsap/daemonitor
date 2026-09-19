package io.github.cdsap.daemonitor.collect

/**
 * Stable process identity for caches that must survive PID reuse: the OS may reassign a PID to a
 * new process, but (pid, startTimeMs) uniquely identifies one lifetime.
 */
data class ProcessIdentity(val pid: Long, val startTimeMs: Long)
