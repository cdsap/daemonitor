package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.DaemonLogLine

internal class GoCoreLogTailTracker {
    private val lastTails = mutableMapOf<Long, List<String>>()

    fun newLinesSince(pid: Long, current: List<String>): List<DaemonLogLine> {
        val previous = lastTails[pid].orEmpty()
        lastTails[pid] = current
        if (previous.isEmpty()) return current.map(::DaemonLogLine)
        if (current.isEmpty()) return emptyList()

        val maxOverlap = minOf(previous.size, current.size)
        for (overlap in maxOverlap downTo 0) {
            if (previous.takeLast(overlap) == current.take(overlap)) {
                return current.drop(overlap).map(::DaemonLogLine)
            }
        }
        return current.map(::DaemonLogLine)
    }
}
