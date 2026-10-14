package io.github.cdsap.daemonitor.coreipc

internal class GoCoreLogTailTracker {
    private val lastTails = mutableMapOf<Long, List<String>>()

    fun newLinesSince(pid: Long, current: List<String>): List<String> {
        val previous = lastTails[pid].orEmpty()
        lastTails[pid] = current
        if (previous.isEmpty()) return current
        if (current.isEmpty()) return emptyList()

        val maxOverlap = minOf(previous.size, current.size)
        for (overlap in maxOverlap downTo 0) {
            if (previous.takeLast(overlap) == current.take(overlap)) {
                return current.drop(overlap)
            }
        }
        return current
    }
}
