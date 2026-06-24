package com.gradlewatcher.ui.common

import com.gradlewatcher.Defaults
import com.gradlewatcher.domain.model.GradleProcess
import com.gradlewatcher.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BadgesTest {

    private fun proc(pid: Long, rss: Long, project: String?) = GradleProcess(
        pid = pid, parentPid = 1, type = ProcessType.GRADLE_DAEMON, commandLine = "java GradleDaemon",
        workingDirectory = project, projectPath = project, cpuPercent = null, rssMemoryMb = rss,
        maxHeapMb = null, minHeapMb = null, gc = null, startTimeMs = 1, status = "RUNNING",
    )

    @Test
    fun `memory badge respects warn and critical thresholds`() {
        assertNull(Badges.memoryBadge(Defaults.MEM_WARN_MB - 1))
        assertEquals(BadgeLevel.WARN, Badges.memoryBadge(Defaults.MEM_WARN_MB))
        assertEquals(BadgeLevel.CRITICAL, Badges.memoryBadge(Defaults.MEM_CRIT_MB))
    }

    @Test
    fun `null rss (sub-poll) never triggers a memory badge`() {
        assertNull(Badges.memoryBadge(null))
    }

    @Test
    fun `concurrent same-project pids are flagged`() {
        val procs = listOf(
            proc(1, 100, "/x"),
            proc(2, 100, "/x"),
            proc(3, 100, "/y"),
        )
        assertEquals(setOf(1L, 2L), Badges.concurrentSameProjectPids(procs))
    }

    @Test
    fun `badge state clears when condition no longer holds (derived recompute)`() {
        // Two builds on /x → both flagged.
        assertTrue(Badges.concurrentSameProjectPids(listOf(proc(1, 100, "/x"), proc(2, 100, "/x"))).isNotEmpty())
        // Next poll: only one remains → no concurrency badge (auto-clear).
        assertTrue(Badges.concurrentSameProjectPids(listOf(proc(1, 100, "/x"))).isEmpty())
    }

    @Test
    fun `null project paths are not grouped as concurrent`() {
        assertTrue(Badges.concurrentSameProjectPids(listOf(proc(1, 100, null), proc(2, 100, null))).isEmpty())
    }
}
