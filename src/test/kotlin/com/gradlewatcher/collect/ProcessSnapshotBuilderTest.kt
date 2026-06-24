package com.gradlewatcher.collect

import com.gradlewatcher.domain.model.PriorSample
import com.gradlewatcher.domain.model.ProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProcess(
    override val pid: Long = 100,
    override val parentPid: Long = 1,
    override val name: String = "java",
    override val commandLine: String,
    override val workingDirectory: String = "/Users/dev/proj",
    override val rssBytes: Long = 512L * 1024 * 1024,
    override val startTimeMs: Long = 1_000,
    override val state: String = "RUNNING",
    override val userId: String = "501",
    override val cpuTimeMs: Long = 0,
) : ProcessInfo

private const val DAEMON_CL =
    "java -Xmx4g -XX:+UseG1GC org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3"

class ProcessSnapshotBuilderTest {

    @Test
    fun `returns null for non-gradle process`() {
        val snap = ProcessSnapshotBuilder.build(
            FakeProcess(commandLine = "node server.js"), null, 2_000, 8,
        )
        assertNull(snap)
    }

    @Test
    fun `builds snapshot with rss in mb and parsed heap`() {
        val snap = ProcessSnapshotBuilder.build(FakeProcess(commandLine = DAEMON_CL), null, 2_000, 8)!!
        assertEquals(512L, snap.rssMemoryMb)
        assertEquals(4096L, snap.maxHeapMb)
        assertEquals("/Users/dev/proj", snap.projectPath)
    }

    @Test
    fun `cpu is null on first sample (never lifetime average)`() {
        val snap = ProcessSnapshotBuilder.build(FakeProcess(commandLine = DAEMON_CL), null, 2_000, 8)!!
        assertNull(snap.cpuPercent)
    }

    @Test
    fun `cpu is delta over wall-clock normalized by processors`() {
        // 1000ms of CPU over a 2000ms wall window on 8 cores = (1000/2000)/8*100 = 6.25%
        val info = FakeProcess(commandLine = DAEMON_CL, cpuTimeMs = 1_000)
        val prior = PriorSample(cpuTimeMs = 0, wallClockMs = 0)
        val snap = ProcessSnapshotBuilder.build(info, prior, 2_000, 8)!!
        assertEquals(6.25, snap.cpuPercent!!, 0.001)
    }

    @Test
    fun `empty working directory becomes null project path`() {
        val snap = ProcessSnapshotBuilder.build(
            FakeProcess(commandLine = DAEMON_CL, workingDirectory = ""), null, 2_000, 8,
        )!!
        assertNull(snap.projectPath)
        assertNull(snap.workingDirectory)
    }

    @Test
    fun `command line is redacted in the snapshot`() {
        val cl = "$DAEMON_CL -Ptoken=supersecret"
        val snap = ProcessSnapshotBuilder.build(FakeProcess(commandLine = cl), null, 2_000, 8)!!
        assertTrue(snap.commandLine.contains("-Ptoken=***"), snap.commandLine)
        assertTrue(!snap.commandLine.contains("supersecret"))
    }
}
