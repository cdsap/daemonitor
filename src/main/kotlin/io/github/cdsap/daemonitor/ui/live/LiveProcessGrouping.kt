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

private fun ProcessType.isHierarchyRoot(): Boolean =
    this == ProcessType.GRADLE_DAEMON || this == ProcessType.BAZEL_SERVER

/**
 * Builds the desktop equivalent of the TUI's grouped view.
 *
 * Gradle daemon and Bazel server rows include the RSS and CPU of all descendants present in
 * the current snapshot; the child rows remain selectable through their original PIDs.
 * Processes with no hierarchy-root ancestor remain ordinary rows.
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
                .filter { it.pid !in visited && !it.type.isHierarchyRoot() }
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

    processes.filter { it.type.isHierarchyRoot() }.forEach { root ->
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
