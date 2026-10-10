package io.github.cdsap.daemonitor.ui.live

import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType

/** A row in the optional grouped process view. [targetPid] always points at a real snapshot. */
internal data class LiveProcessRow(
    val process: GradleProcess,
    val targetPid: Long = process.pid,
    val depth: Int = 0,
    val isGroupRoot: Boolean = false,
    val groupChildCount: Int = 0,
)

/**
 * Builds the desktop equivalent of the TUI's grouped view.
 *
 * Gradle daemon rows include the RSS and CPU of all descendants present in the current snapshot;
 * the child rows remain selectable through their original PIDs. Processes with no daemon
 * ancestor remain ordinary rows.
 */
internal fun groupedLiveProcessRows(processes: List<GradleProcess>): List<LiveProcessRow> {
    val childrenByParent = processes.groupBy { it.parentPid }
    val groupedPids = mutableSetOf<Long>()
    val groupedRoots = mutableSetOf<Long>()
    val rows = mutableListOf<LiveProcessRow>()

    fun descendants(rootPid: Long): List<Pair<GradleProcess, Int>> {
        val result = mutableListOf<Pair<GradleProcess, Int>>()
        val visited = mutableSetOf<Long>()

        fun visit(parentPid: Long, depth: Int) {
            childrenByParent[parentPid]
                .orEmpty()
                .asSequence()
                .filter { it.pid !in visited && it.type != ProcessType.GRADLE_DAEMON }
                .sortedBy { it.pid }
                .forEach { child ->
                    visited += child.pid
                    result += child to depth
                    visit(child.pid, depth + 1)
                }
        }

        visit(rootPid, 1)
        return result
    }

    processes.filter { it.type == ProcessType.GRADLE_DAEMON }.forEach { root ->
        val children = descendants(root.pid)
        if (children.isEmpty()) return@forEach

        groupedRoots += root.pid
        groupedPids += children.map { it.first.pid }
        val cpuValues = listOfNotNull(root.cpuPercent) + children.mapNotNull { it.first.cpuPercent }
        val aggregate = root.copy(
            rssMemoryMb = root.rssMemoryMb + children.sumOf { it.first.rssMemoryMb },
            cpuPercent = cpuValues.takeIf { it.isNotEmpty() }?.sum(),
        )
        rows += LiveProcessRow(
            process = aggregate,
            targetPid = root.pid,
            isGroupRoot = true,
            groupChildCount = children.size,
        )
        rows += children.map { (child, depth) ->
            LiveProcessRow(process = child, targetPid = child.pid, depth = depth)
        }
    }

    rows += processes
        .filter { it.pid !in groupedPids && it.pid !in groupedRoots }
        .map { LiveProcessRow(process = it) }
    return rows
}
