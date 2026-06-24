package com.gradlewatcher.ui.common

import com.gradlewatcher.Defaults
import com.gradlewatcher.domain.model.GradleProcess
import com.gradlewatcher.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BadgesTest {

    private fun proc(
        pid: Long,
        rss: Long,
        project: String?,
        type: ProcessType = ProcessType.GRADLE_WRAPPER,
    ) = GradleProcess(
        pid = pid, parentPid = 1, type = type, commandLine = "java GradleDaemon",
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
    fun `two wrapper invocations on one project flag that project`() {
        val procs = listOf(
            proc(1, 100, "/x", ProcessType.GRADLE_WRAPPER),
            proc(2, 100, "/x", ProcessType.GRADLE_WRAPPER),
            proc(3, 100, "/y", ProcessType.GRADLE_WRAPPER),
        )
        assertEquals(setOf(1L, 2L), Badges.concurrentSameProjectPids(procs))
    }

    @Test
    fun `a single build's many processes sharing a cwd are NOT flagged`() {
        // One build = one wrapper + its daemon + a java worker, all in /x. Not "multiple builds".
        val procs = listOf(
            proc(1, 100, "/x", ProcessType.GRADLE_WRAPPER),
            proc(2, 500, "/x", ProcessType.GRADLE_DAEMON),
            proc(3, 100, "/x", ProcessType.JAVA_GRADLE_RELATED),
        )
        assertTrue(Badges.concurrentSameProjectPids(procs).isEmpty())
    }

    @Test
    fun `badge clears when a second build is no longer present (derived recompute)`() {
        val twoBuilds = listOf(proc(1, 100, "/x", ProcessType.GRADLE_WRAPPER), proc(2, 100, "/x", ProcessType.GRADLE_WRAPPER))
        assertTrue(Badges.concurrentSameProjectPids(twoBuilds).isNotEmpty())
        // Next poll: one wrapper left → no badge.
        assertTrue(Badges.concurrentSameProjectPids(listOf(proc(1, 100, "/x", ProcessType.GRADLE_WRAPPER))).isEmpty())
    }

    @Test
    fun `null project paths are never flagged`() {
        val procs = listOf(proc(1, 100, null, ProcessType.GRADLE_WRAPPER), proc(2, 100, null, ProcessType.GRADLE_WRAPPER))
        assertTrue(Badges.concurrentSameProjectPids(procs).isEmpty())
    }
}
